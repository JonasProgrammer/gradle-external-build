package co.arichardson.gradle.make.tasks

import org.gradle.api.tasks.Exec
import org.gradle.process.internal.ExecException

class OutputRedirectingExec extends Exec {
    boolean redirectOutput = false

    final File logFile
    final File errorFile

    public OutputRedirectingExec() {
        logFile = project.file("${temporaryDir}/output.txt")
        errorFile = project.file("${temporaryDir}/errors.txt")
    }

    @Override
    protected void exec() {
        if (redirectOutput) {
            logFile.parentFile.mkdirs()
            errorFile.parentFile.mkdirs()
            standardOutput = logFile.newOutputStream()
            errorOutput = errorFile.newOutputStream()

            ignoreExitValue = true
            super.exec()

            if (execResult.exitValue != 0) {
                throw new ExecException("Exec failed with code ${execResult.exitValue}.\nSee the full log at ${errorFile}")
            }
        } else {
            super.exec()
        }
    }
}
