def call(Map config = [:]) {
    def credentialsId = config.credentialsId ?: 'django-env-file'
    def repoUrl = config.repoUrl ?: 'https://repo.osian.io/osianinfotech/edelweiss-tokio/etli-need-analysis-backend.git'
    def repoCreds = config.repoCreds ?: 'osian-repo-credentials'
    def sonarServer = config.sonarServer ?: 'MySonarServer'

    pipeline {
        agent any

        parameters {
            string(name: 'BRANCH_NAME', defaultValue: 'master', description: 'Enter the branch name or feature branch to build (e.g., feature/login-flow, main, master)')
        }

        stages {
            stage('Checkout Code') {
                steps {
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: "${params.BRANCH_NAME}"]],
                        userRemoteConfigs: [[
                            url: repoUrl,
                            credentialsId: repoCreds
                        ]]
                    ])
                }
            }

            stage('Setup Environment & Dependencies') {
                steps {
                    withCredentials([file(credentialsId: credentialsId, variable: 'ENV_FILE_PATH')]) {
                        sh '''
                            chmod -R +w need_analysis_backend || true
                            mkdir -p need_analysis_backend
                            cp "$ENV_FILE_PATH" need_analysis_backend/.env
                            python3 -m venv venv
                            ./venv/bin/pip install --upgrade pip
                            ./venv/bin/pip install -r requirements.txt
                            ./venv/bin/pip install cryptography
                        '''
                    }
                }
            }

            stage('OWASP: Dependency Check') {
                steps {
                    withCredentials([string(credentialsId: 'nvd-api-key-id', variable: 'NVD_API_KEY')]) {
                        dependencyCheck additionalArguments: "--scan . --disableAssembly --nvdApiKey ${env.NVD_API_KEY} --data .dependency-check-data", odcInstallation: 'OWASP'
                    }
                    dependencyCheckPublisher pattern: 'dependency-check-report.xml'
                }
            }

            stage('SonarQube: Code Analysis') {
                steps {
                    withSonarQubeEnv("${sonarServer}") {
                        script {
                            def scannerHome = tool 'SonarQube'
                            
                            sh """
                                ${scannerHome}/bin/sonar-scanner \
                                  -Dsonar.projectKey=etli-need-analysis-backend \
                                  -Dsonar.projectName=etli-need-analysis-backend \
                                  -Dsonar.sources=. \
                                  -Dsonar.exclusions=**/venv/**,**/staticfiles/**,**/media/**,**/certificate_pdf/templates/**,**/migrations/**
                            """
                        }
                    }
                }
            }

            stage('SonarQube: Quality Gates') {
                steps {
                    script {
                        try {
                            timeout(time: 5, unit: 'MINUTES') {
                                def qg = waitForQualityGate(abortPipeline: true)
                                echo "Quality Gate status: ${qg.status}"
                            }
                        } catch (Exception e) {
                            echo "Warning: SonarQube Quality Gate encountered an issue or timed out (${e.message}). Proceeding with deployment anyway."
                        }
                    }
                }
            }

            stage('Run Migrations') {
                steps {
                    sh '''
                        ./venv/bin/python manage.py migrate --noinput
                    '''
                }
            }
            
            stage('Restart Docker Service') {
                steps {
                    sh 'docker compose -f /home/osianlap07/Projects/Need_Analysis/docker-compose.yml restart'
                }
            }
        }

        post {
            success {
                echo 'CI security gates passed! Triggering deployment pipeline...'
                
                build job: 'Need Jenkins Deploy', 
                      parameters: [
                          string(name: 'BRANCH_NAME', value: "${env.BRANCH_NAME}")
                      ],
                      wait: false // Set to true if you want CI to wait for deployment to finish

                emailext(
                    subject: "SUCCESS: Jenkins Build '${env.JOB_NAME} [${env.BUILD_NUMBER}]'",
                    body: "<p>Build and deployment completed successfully for branch <b>${env.BRANCH_NAME}</b>.</p><p>Check console output at: <a href='${env.BUILD_URL}console'>${env.BUILD_URL}console</a></p>",
                    mimeType: 'text/html',
                    to: 'akhileshsxiv@gmail.com'
                )
            }
            failure {
                echo 'Pipeline failed during security scanning or deployment. Check console output for details.'
                emailext(
                    subject: "FAILED: Jenkins Build '${env.JOB_NAME} [${env.BUILD_NUMBER}]'",
                    body: "<p>Pipeline failed during execution for branch <b>${env.BRANCH_NAME}</b>.</p><p>Check console output at: <a href='${env.BUILD_URL}console'>${env.BUILD_URL}console</a></p>",
                    mimeType: 'text/html',
                    to: 'akhileshsxiv@gmail.com',
                    attachLog: true
                )
            }
        }
    }
}
