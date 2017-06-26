package com.cisco.gradle.externalbuild

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

import static org.gradle.testkit.runner.TaskOutcome.FAILED
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class ExternalBuildTest extends Specification {
    @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
    File buildFile
    File outputFile

    static final String pluginInit = """
        plugins {
            id 'com.cisco.external-build'
        }

        import com.cisco.gradle.externalbuild.ExternalNativeExecutableSpec
        import com.cisco.gradle.externalbuild.ExternalNativeLibrarySpec
        import com.cisco.gradle.externalbuild.tasks.CMake
        import com.cisco.gradle.externalbuild.tasks.GnuMake
    """

    def setup() {
        buildFile = testProjectDir.newFile('build.gradle')
        outputFile = testProjectDir.newFile()
    }

    BuildResult runBuild(boolean succeed=true) {
        GradleRunner runner = GradleRunner.create()
                .withPluginClasspath()
                .withProjectDir(testProjectDir.root)
                .withArguments('build')
        return succeed ? runner.build() : runner.buildAndFail()
    }

    String outputText(String component, String buildType=null) {
        File outputFolder = new File(testProjectDir.root, "build/tmp/externalBuild${component.capitalize()}")
        if (buildType == null) {
            return new File(outputFolder, 'output.txt').text.trim()
        } else {
            return new File(outputFolder, "${buildType}-output.txt").text.trim()
        }
    }

    List<String> folderContents(File folder, String subfolder='') {
        List<String> filenames = new File(folder, subfolder).listFiles()*.name
        if (filenames != null) {
            Collections.sort(filenames)
        }
        return filenames
    }

    def "no output"() {
        given:
        buildFile << """
            $pluginInit

            model {
                components {
                    foo(ExternalNativeExecutableSpec) {
                        buildConfig(GnuMake) {
                            executable 'echo'
                        }
                    }
                }
            }
        """

        when:
        def result = runBuild()

        then:
        result.task(":build").outcome == SUCCESS
        folderContents(testProjectDir.root, 'build/exe/foo') == null
    }

    def "basic make"() {
        given:
        buildFile << """
            $pluginInit

            model {
                components {
                    foo(ExternalNativeExecutableSpec) {
                        buildConfig(GnuMake) {
                            executable 'echo'
                            jobs 1
                            targets 'all', 'install'
                            args 'make-arg-1'
                        }

                        buildOutput {
                            outputFile = file('${outputFile.path}')
                        }
                    }
                }
            }
        """

        when:
        def result = runBuild()

        then:
        result.task(":build").outcome == SUCCESS
        outputText('fooExecutable', 'makeAll') == 'make-arg-1 -j 1 all'
        outputText('fooExecutable', 'makeInstall') == 'make-arg-1 -j 1 install'
        folderContents(testProjectDir.root, 'build/exe/foo') == ['foo']
    }

    def "basic cmake"() {
        given:
        buildFile << """
            $pluginInit

            model {
                components {
                    foo(ExternalNativeExecutableSpec) {
                        buildConfig(CMake) {
                            executable 'echo'
                            jobs 1
                            targets 'all'
                            cmakeExecutable 'echo'
                            cmakeArgs 'cmake-arg-1'
                        }

                        buildOutput {
                            outputFile = file('${outputFile.path}')
                        }
                    }
                }
            }
        """

        when:
        def result = runBuild()

        then:
        result.task(":build").outcome == SUCCESS
        outputText('fooExecutable', 'cmake') == 'cmake-arg-1'
        outputText('fooExecutable', 'makeAll') == '-j 1 all'
        folderContents(testProjectDir.root, 'build/exe/foo') == ['foo']
    }

    def "two config blocks"() {
        given:
        buildFile << """
            $pluginInit

            model {
                components {
                    foo(ExternalNativeExecutableSpec) {
                        buildConfig(GnuMake) {
                            executable 'echo'
                            jobs 1
                            targets 'all'
                            args 'make-arg-1'
                        }

                        buildConfig {
                            args 'make-arg-2'
                        }

                        buildOutput {
                            outputFile = file('${outputFile.path}')
                        }
                    }
                }
            }
        """

        when:
        def result = runBuild()

        then:
        result.task(":build").outcome == SUCCESS
        outputText('fooExecutable', 'makeAll') == 'make-arg-1 make-arg-2 -j 1 all'
        folderContents(testProjectDir.root, 'build/exe/foo') == ['foo']
    }

    def "cannot set task type twice"() {
        given:
        buildFile << """
            $pluginInit

            model {
                components {
                    foo(ExternalNativeExecutableSpec) {
                        buildConfig(GnuMake) {
                            executable 'echo'
                            jobs 1
                            targets 'all'
                            args 'make-arg-1'
                        }

                        buildConfig(CMake) {
                            args 'make-arg-2'
                        }

                        buildOutput {
                            outputFile = file('${outputFile.path}')
                        }
                    }
                }
            }
        """

        when:
        def result = runBuild(false)

        then:
        result.tasks == []
    }

    def "inherited config"() {
        given:
        buildFile << """
            $pluginInit

            model {
                components {
                    foo(ExternalNativeExecutableSpec) {
                        buildConfig(GnuMake) {
                            executable 'echo'
                            jobs 1
                            targets 'all'
                            args 'make-arg-1'
                        }

                        buildOutput {
                            outputFile = file('${outputFile.path}')
                        }
                    }

                    bar(ExternalNativeExecutableSpec) {
                        buildConfig(\$.components.foo) {
                            args 'make-arg-2'
                        }

                        buildOutput {
                            outputFile = file('${outputFile.path}')
                        }
                    }
                }
            }
        """

        when:
        def result = runBuild()

        then:
        result.task(":build").outcome == SUCCESS
        outputText('fooExecutable', 'makeAll') == 'make-arg-1 -j 1 all'
        outputText('barExecutable', 'makeAll') == 'make-arg-1 make-arg-2 -j 1 all'
        folderContents(testProjectDir.root, 'build/exe/foo') == ['foo']
        folderContents(testProjectDir.root, 'build/exe/bar') == ['bar']
    }

    def "external executable depends on Gradle library"() {
        given:
        buildFile << """
            $pluginInit

            model {
                components {
                    foo(ExternalNativeExecutableSpec) {
                        buildConfig(GnuMake) {
                            lib library: 'bar'

                            executable 'echo'
                            args(*requiredLibraries)
                        }

                        buildOutput {
                            outputFile = file('${outputFile.path}')
                        }
                    }

                    bar(NativeLibrarySpec) {
                        sources {
                            cpp {
                                source {
                                    srcDir '.'
                                    include 'lib.cpp'
                                }
                            }
                        }
                    }
                }
            }
        """

        File srcFile = testProjectDir.newFile('lib.cpp')
        srcFile.text = "int bar() { return 0; }"

        when:
        def result = runBuild()

        then:
        result.task(":build").outcome == SUCCESS
        outputText('fooExecutable').contains('libbar.so')
        folderContents(testProjectDir.root, 'build/exe/foo') == ['foo']
    }

    def "Gradle executable depends on external library"() {
        given:
        buildFile << """
            $pluginInit

            model {
                components {
                    foo(ExternalNativeLibrarySpec) {
                        buildConfig(GnuMake) {
                            executable 'echo'
                        }

                        buildOutput {
                            outputFile = file('${outputFile.path}')
                        }
                    }

                    bar(NativeExecutableSpec) {
                        sources {
                            cpp {
                                source {
                                    srcDir '.'
                                    include 'main.cpp'
                                }

                                lib library: 'foo'
                            }
                        }
                    }
                }
            }
        """

        File srcFile = testProjectDir.newFile('main.cpp')
        srcFile.text = "int main() { return 0; }"

        when:
        def result = runBuild(false)

        then:
        result.task(":fooSharedLibrary").outcome == SUCCESS
        result.task(":linkBarExecutable").outcome == FAILED
        result.output.contains('libfoo.so')
        folderContents(testProjectDir.root, 'build/libs/foo/shared') == ['libfoo.so']
    }
}
