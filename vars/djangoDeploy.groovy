def call(Map config = [:]) {
    def workspacePath = config.workspacePath ?: '/home/osianlap07/Projects/Need_Analysis/etli-need-analysis-backend'
    def rootPath = '/home/osianlap07/Projects/Need_Analysis'
    def credentialsId = config.credentialsId ?: 'django-env-file'
    def repoUrl = config.repoUrl ?: 'https://repo.osian.io/osianinfotech/edelweiss-tokio/etli-need-analysis-backend.git'
    def repoCreds = config.repoCreds ?: 'osian-repo-credentials'
    def sonarServer = config.sonarServer ?: 'MySonarServer'

    pipeline {
        agent any

        environment {
            SONAR_HOME = tool "${sonarServer}"
        }

        stages {
            stage('Checkout Code') {
                steps {
                    dir(workspacePath) {
                        checkout([
                            $class: 'GitSCM',
                            branches: [[name: '*/master']],
                            userRemoteConfigs: [[
                                url: repoUrl,
                                credentialsId: repoCreds
                            ]]
                        ])
                    }
                }
            }

            stage('Setup Environment & Dependencies') {
                steps {
                    dir(workspacePath) {
                        withCredentials([file(credentialsId: credentialsId, variable: 'ENV_FILE_PATH')]) {
                            sh '''
                                cp $ENV_FILE_PATH need_analysis_backend/.env
                                python3 -m venv venv
                                ./venv/bin/pip install --upgrade pip
                                ./venv/bin/pip install -r requirements.txt
                                ./venv/bin/pip install cryptography
                            '''
                        }
                    }
                }
            }

            stage('OWASP: Dependency Check') {
                steps {
                    dir(workspacePath) {
                        dependencyCheck additionalArguments: '--scan . --disableAssembly', odcInstallation: 'Default'
                        dependencyCheckPublisher pattern: 'dependency-check-report.xml'
                    }
                }
            }

            stage('SonarQube: Code Analysis') {
                steps {
                    dir(workspacePath) {
                        withSonarQubeEnv("${sonarServer}") {
                            sh '''
                                ./venv/bin/pip install sonar-scanner || true
                                sonar-scanner \
                                  -Dsonar.projectKey=etli-need-analysis-backend \
                                  -Dsonar.projectName=etli-need-analysis-backend \
                                  -Dsonar.sources=. \
                                  -Dsonar.exclusions=**/venv/**,**/staticfiles/**,**/media/**
                            '''
                        }
                    }
                }
            }

            stage('SonarQube: Quality Gates') {
                steps {
                    timeout(time: 10, unit: 'MINUTES') {
                        waitForQualityGate abortPipeline: true
                    }
                }
            }

            stage('Run Migrations') {
                steps {
                    dir(workspacePath) {
                        sh '''
                            ./venv/bin/python manage.py migrate --noinput
                        '''
                    }
                }
            }

            stage('Restart Docker Service') {
                steps {
                    dir(rootPath) {
                        sh '''
                            docker compose up -d --build etli-backend-service
                        '''
                    }
                }
            }
        }

        post {
            success {
                echo 'CI security gates passed and Docker Compose service updated successfully!'
            }
            failure {
                echo 'Pipeline failed during security scanning or deployment. Check console output for details.'
            }
        }
    }
}
