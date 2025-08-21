
    import jetbrains.buildServer.configs.kotlin.v2019_2.*
    import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.script
    import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.maven
    import jetbrains.buildServer.configs.kotlin.v2019_2.triggers.vcs
    import jetbrains.buildServer.configs.kotlin.v2019_2.triggers.schedule

    version = "2019.2"

    project {
        description = "Large performance test project with multiple build configurations"

        buildType(UnitTests)
        buildType(IntegrationTests)
        buildType(CodeAnalysis)
        buildType(SecurityScan)
        buildType(PerformanceTests)
        buildType(UITests)
        buildType(BuildDocumentation)
        buildType(DeployStaging)
        buildType(DeployProduction)
        buildType(NotificationPipeline)

        // Project parameters
        params {
            param("env.PROJECT_VERSION", "1.0.0")
            param("env.BUILD_ENVIRONMENT", "ci")
            param("system.debug.mode", "false")
            param("teamcity.build.branch", "main")
        }
    }

    object UnitTests : BuildType({
        name = "Unit Tests"
        description = "Run unit tests with coverage reporting"

        artifactRules = "target/test-results/** => test-results.zip"

        vcs {
            root(DslContext.settingsRoot)
            cleanCheckout = true
        }

        steps {
            script {
                name = "Setup Environment"
                scriptContent = """
                    echo "Setting up test environment"
                    export TEST_ENV=unit
                    mkdir -p target/test-results
                """.trimIndent()
            }

            maven {
                name = "Run Unit Tests"
                goals = "clean test"
                mavenVersion = bundled_3_6()
                jdkHome = "%env.JDK_11%"
            }

            script {
                name = "Generate Coverage Report"
                scriptContent = """
                    echo "Generating coverage report"
                    mvn jacoco:report
                """.trimIndent()
            }
        }

        triggers {
            vcs {
                branchFilter = "+:*"
            }
        }

        features {
            commitStatusPublisher {
                vcsRootExtId = "${DslContext.settingsRoot.id}"
                publisher = github {
                    githubUrl = "https://api.github.com"
                    authType = personalToken {
                        token = "credentialsJSON:github-token"
                    }
                }
            }
        }

        requirements {
            moreThan("teamcity.agent.hardware.memorySizeMb", "4096")
        }
    })

    object IntegrationTests : BuildType({
        name = "Integration Tests"
        description = "Run integration tests with database setup"

        dependencies {
            snapshot(UnitTests) {
                onDependencyFailure = FailureAction.FAIL_TO_START
            }
        }

        vcs {
            root(DslContext.settingsRoot)
            cleanCheckout = true
        }

        steps {
            script {
                name = "Start Test Database"
                scriptContent = """
                    echo "Starting test database"
                    docker run -d --name test-db -p 5432:5432 -e POSTGRES_PASSWORD=test postgres:13
                    sleep 10
                """.trimIndent()
            }

            maven {
                name = "Run Integration Tests"
                goals = "verify -Pintegration-tests"
                mavenVersion = bundled_3_6()
                jdkHome = "%env.JDK_11%"
            }

            script {
                name = "Cleanup Test Database"
                scriptContent = """
                    echo "Cleaning up test database"
                    docker stop test-db || true
                    docker rm test-db || true
                """.trimIndent()
                executionPolicy = BuildStep.ExecutionPolicy.ALWAYS
            }
        }

        requirements {
            contains("teamcity.agent.jvm.os.name", "Linux")
            exists("docker")
        }
    })

    object CodeAnalysis : BuildType({
        name = "Code Analysis"
        description = "Static code analysis with SonarQube"

        dependencies {
            snapshot(UnitTests) {}
        }

        vcs {
            root(DslContext.settingsRoot)
        }

        steps {
            maven {
                name = "SonarQube Analysis"
                goals = "sonar:sonar"
                mavenVersion = bundled_3_6()
                jdkHome = "%env.JDK_11%"
                runnerArgs = """
                    -Dsonar.host.url=%env.SONAR_URL%
                    -Dsonar.login=%env.SONAR_TOKEN%
                    -Dsonar.projectKey=TestProj_d6704975
                    -Dsonar.projectName="TestProj_d6704975 Analysis"
                """.trimIndent()
            }

            script {
                name = "Quality Gate Check"
                scriptContent = """
                    echo "Checking SonarQube quality gate"
                    # Add quality gate check logic here
                """.trimIndent()
            }
        }
    })

    object SecurityScan : BuildType({
        name = "Security Scan"
        description = "Security vulnerability scanning"

        vcs {
            root(DslContext.settingsRoot)
        }

        steps {
            script {
                name = "OWASP Dependency Check"
                scriptContent = """
                    echo "Running OWASP dependency check"
                    mvn org.owasp:dependency-check-maven:check
                """.trimIndent()
            }

            script {
                name = "Trivy Scan"
                scriptContent = """
                    echo "Running Trivy vulnerability scan"
                    trivy fs . --format json --output trivy-report.json
                """.trimIndent()
            }
        }

        triggers {
            schedule {
                schedulingPolicy = daily {
                    hour = 2
                }
            }
        }
    })

    object PerformanceTests : BuildType({
        name = "Performance Tests"
        description = "Load and performance testing"

        dependencies {
            snapshot(DeployStaging) {}
        }

        vcs {
            root(DslContext.settingsRoot)
        }

        steps {
            script {
                name = "JMeter Performance Tests"
                scriptContent = """
                    echo "Running JMeter performance tests"
                    jmeter -n -t performance-tests.jmx -l results.jtl
                """.trimIndent()
            }

            script {
                name = "Generate Performance Report"
                scriptContent = """
                    echo "Generating performance report"
                    jmeter -g results.jtl -o performance-report/
                """.trimIndent()
            }
        }

        requirements {
            moreThan("teamcity.agent.hardware.cpuCount", "4")
        }
    })

    object UITests : BuildType({
        name = "UI Tests"
        description = "Automated UI tests with Selenium"

        dependencies {
            snapshot(DeployStaging) {}
        }

        vcs {
            root(DslContext.settingsRoot)
        }

        steps {
            script {
                name = "Start Selenium Grid"
                scriptContent = """
                    echo "Starting Selenium Grid"
                    docker-compose -f selenium-docker-compose.yml up -d
                    sleep 15
                """.trimIndent()
            }

            maven {
                name = "Run UI Tests"
                goals = "test -Pui-tests"
                mavenVersion = bundled_3_6()
                jdkHome = "%env.JDK_11%"
            }

            script {
                name = "Stop Selenium Grid"
                scriptContent = """
                    echo "Stopping Selenium Grid"
                    docker-compose -f selenium-docker-compose.yml down
                """.trimIndent()
                executionPolicy = BuildStep.ExecutionPolicy.ALWAYS
            }
        }
    })

    object BuildDocumentation : BuildType({
        name = "Build Documentation"
        description = "Generate project documentation"

        vcs {
            root(DslContext.settingsRoot)
        }

        steps {
            script {
                name = "Generate API Docs"
                scriptContent = """
                    echo "Generating API documentation"
                    mvn javadoc:javadoc
                """.trimIndent()
            }

            script {
                name = "Build User Manual"
                scriptContent = """
                    echo "Building user manual"
                    gitbook build docs/ public/
                """.trimIndent()
            }
        }

        artifactRules = "target/site/** => documentation.zip"
    })

    object DeployStaging : BuildType({
        name = "Deploy to Staging"
        description = "Deploy application to staging environment"

        dependencies {
            snapshot(IntegrationTests) {}
            snapshot(CodeAnalysis) {}
            snapshot(SecurityScan) {}
        }

        vcs {
            root(DslContext.settingsRoot)
        }

        steps {
            maven {
                name = "Build Application"
                goals = "clean package -Pstaging"
                mavenVersion = bundled_3_6()
                jdkHome = "%env.JDK_11%"
            }

            script {
                name = "Deploy to Staging"
                scriptContent = """
                    echo "Deploying to staging environment"
                    kubectl apply -f k8s/staging/
                    kubectl rollout status deployment/app-staging
                """.trimIndent()
            }

            script {
                name = "Health Check"
                scriptContent = """
                    echo "Running health check"
                    curl -f http://staging.example.com/health || exit 1
                """.trimIndent()
            }
        }
    })

    object DeployProduction : BuildType({
        name = "Deploy to Production"
        description = "Deploy application to production environment"

        dependencies {
            snapshot(PerformanceTests) {}
            snapshot(UITests) {}
        }

        vcs {
            root(DslContext.settingsRoot)
        }

        steps {
            maven {
                name = "Build Production Package"
                goals = "clean package -Pproduction"
                mavenVersion = bundled_3_6()
                jdkHome = "%env.JDK_11%"
            }

            script {
                name = "Deploy to Production"
                scriptContent = """
                    echo "Deploying to production environment"
                    kubectl apply -f k8s/production/
                    kubectl rollout status deployment/app-production
                """.trimIndent()
            }

            script {
                name = "Production Health Check"
                scriptContent = """
                    echo "Running production health check"
                    curl -f http://production.example.com/health || exit 1
                """.trimIndent()
            }
        }

        triggers {
            schedule {
                schedulingPolicy = weekly {
                    dayOfWeek = ScheduleTrigger.DAY.Saturday
                    hour = 3
                }
            }
        }
    })

    object NotificationPipeline : BuildType({
        name = "Notification Pipeline"
        description = "Send notifications based on build results"

        dependencies {
            snapshot(DeployProduction) {
                onDependencyFailure = FailureAction.RUN_ADD_PROBLEM
            }
        }

        vcs {
            root(DslContext.settingsRoot)
        }

        steps {
            script {
                name = "Send Slack Notification"
                scriptContent = """
                    echo "Sending Slack notification"
                    curl -X POST -H 'Content-type: application/json'                       --data '{"text":"Deployment completed for TestProj_d6704975"}'                       %env.SLACK_WEBHOOK_URL%
                """.trimIndent()
            }

            script {
                name = "Update Dashboard"
                scriptContent = """
                    echo "Updating deployment dashboard"
                    # Add dashboard update logic here
                """.trimIndent()
            }
        }
    })
    