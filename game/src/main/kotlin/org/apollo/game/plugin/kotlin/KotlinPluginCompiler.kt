package org.apollo.game.plugin.kotlin

import com.google.common.base.CaseFormat
import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.*
import org.jetbrains.kotlin.cli.jvm.compiler.*
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.codegen.CompilationException
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.script.KotlinScriptDefinitionFromAnnotatedTemplate
import java.io.File
import java.lang.management.ManagementFactory
import java.net.URISyntaxException
import java.net.URLClassLoader
import java.nio.file.*
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.*
import java.util.function.BiPredicate

class KotlinMessageCollector : MessageCollector {

    override fun clear() {
    }

    override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageLocation) {
        if (severity.isError) {
            println("${location.path}:${location.line}-${location.column}: $message")
            println(">>> ${location.lineContent}")
        }
    }

    override fun hasErrors(): Boolean {
        return false
    }

}

data class KotlinCompilerResult(val fqName: String, val outputPath: Path)

class KotlinPluginCompiler(val classpath: List<File>, val messageCollector: MessageCollector) {

    companion object {

        private val maxSearchDepth = 1024;

        fun currentClasspath(): List<File> {
            val classLoader = Thread.currentThread().contextClassLoader as? URLClassLoader ?:
                    throw RuntimeException("Unable to resolve classpath for current ClassLoader")

            val classpathUrls = classLoader.urLs
            val classpath = ArrayList<File>()

            for (classpathUrl in classpathUrls) {
                try {
                    classpath.add(File(classpathUrl.toURI()))
                } catch (e: URISyntaxException) {
                    throw RuntimeException("URL returned by ClassLoader is invalid")
                }

            }

            return classpath
        }

        @JvmStatic
        fun main(args: Array<String>) {
            if (args.size < 4) throw RuntimeException("Usage: <inputDir> <outputDir> <manifestPath> <classpath>")

            val inputDir = Paths.get(args[0])
            val outputDir = Paths.get(args[1])
            val manifestPath = Paths.get(args[2])
            val classpathEntries = args[3].split(':')
            val classpath = mutableListOf<File>()

            val runtimeBean = ManagementFactory.getRuntimeMXBean()
            if (!runtimeBean.isBootClassPathSupported) {
                println("Warning!  Boot class path is not supported, must be supplied on the command line")
            } else {
                val bootClasspath = runtimeBean.bootClassPath
                classpath.addAll(bootClasspath.split(':').map { File(it) })
            }

            /**
             * Classpath entries on the command line contain the kotlin runtime
             * and plugin API code.  We use our current classpath to provide
             * Kotlin with access to the JRE and apollo modules.
             */
            classpath.addAll(currentClasspath())
            classpath.addAll(classpathEntries.map { File(it) })

            val inputScriptsMatcher = { path: Path, _: BasicFileAttributes -> path.toString().endsWith(".plugin.kts") }
            val inputScripts = Files.find(inputDir, maxSearchDepth, BiPredicate(inputScriptsMatcher))

            val compiler = KotlinPluginCompiler(classpath, MessageCollector.NONE)
            val compiledScriptClasses = mutableListOf<String>()

            try {
                try {
                    Files.createDirectory(outputDir)
                } catch (e: FileAlreadyExistsException) {
                    // do nothing...
                }

                inputScripts.forEach {
                    compiledScriptClasses.add(compiler.compile(it, outputDir).fqName)
                }

                Files.write(manifestPath, compiledScriptClasses, CREATE, TRUNCATE_EXISTING)
            } catch (t: Throwable) {
                t.printStackTrace()
                System.exit(1)
            }
        }
    }

    private fun createCompilerConfiguration(inputPath: Path): CompilerConfiguration {
        val configuration = CompilerConfiguration()
        val scriptDefinition = KotlinScriptDefinitionFromAnnotatedTemplate(KotlinPluginScript::class)

        configuration.add(JVMConfigurationKeys.SCRIPT_DEFINITIONS, scriptDefinition)
        configuration.put(JVMConfigurationKeys.CONTENT_ROOTS, classpath.map { JvmClasspathRoot(it) })
        configuration.put(JVMConfigurationKeys.RETAIN_OUTPUT_IN_MEMORY, true)
        configuration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, KotlinMessageCollector())
        configuration.put(CommonConfigurationKeys.MODULE_NAME, inputPath.toString())
        configuration.addKotlinSourceRoot(inputPath.toAbsolutePath().toString())

        return configuration
    }

    @Throws(KotlinPluginCompilerException::class)
    fun compile(inputPath: Path, outputPath: Path): KotlinCompilerResult {
        val rootDisposable = Disposer.newDisposable()
        val configuration = createCompilerConfiguration(inputPath)

        val configFiles = EnvironmentConfigFiles.JVM_CONFIG_FILES
        val environment = KotlinCoreEnvironment.createForProduction(rootDisposable, configuration, configFiles)

        try {
            val generationState = KotlinToJVMBytecodeCompiler.analyzeAndGenerate(environment)
            if (generationState == null) {
                throw KotlinPluginCompilerException("Failed to generate bytecode for kotlin script")
            }

            val sourceFiles = environment.getSourceFiles()
            val script = sourceFiles[0].script ?: throw KotlinPluginCompilerException("Main script file isnt a script")

            val scriptFilePath = script.fqName.asString().replace('.', '/') + ".class"
            val scriptFileClass = generationState.factory.get(scriptFilePath)

            if (scriptFileClass == null) {
                throw KotlinPluginCompilerException("Unable to find compiled plugin class file $scriptFilePath")
            }

            generationState.factory.asList().forEach {
                Files.write(outputPath.resolve(it.relativePath), it.asByteArray(), CREATE, WRITE, TRUNCATE_EXISTING)
            }

            return KotlinCompilerResult(script.fqName.asString(), outputPath.resolve(scriptFileClass.relativePath))
        } catch (e: CompilationException) {
            throw KotlinPluginCompilerException("Compilation failed", e)
        } finally {
            Disposer.dispose(rootDisposable)
        }
    }

}

class KotlinPluginCompilerException(message: String, cause: Throwable? = null) : Exception(message, cause) {

}