
import jetbrains.buildServer.configs.kotlin.v2019_2.*
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.script

version = "2019.2"

project {
    buildType(Build)
}

object Build : BuildType({
    name = "Build"

    steps {
        script {
            scriptContent = "echo 'Hello from Kotlin DSL!'"
        }
    }
})
