def call(Map config = [:]) {
    def workspacePath = config.workspacePath ?: '/home/osianlap07/Practice/Test_Need'
    def credentialsId = config.credentialsId ?: 'django-env-file'
    def repoUrl = config.repoUrl ?: 'https://repo.osian.io/osianinfotech/edelweiss-tokio/etli-need-analysis-backend.git'
    def repoCreds = config.repoCreds ?: 'osian-repo-credentials'
    def serverPort = config.serverPort ?: '8000'

    pipeline {
        agent any

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
            
            stage('Run Migrations') {
                steps {
                    dir(workspacePath) {
                        sh '''
                            ./venv/bin/python manage.py migrate --noinput
                        '''
                    }
                }
            }

            stage('Start Production Service') {
                steps {
                    dir(workspacePath) {
                        sh """
                            BUILD_ID=dontKillMe nohup ./venv/bin/python manage.py runserver 0.0.0.0:${serverPort} > server.log 2>&1 &
                        """
                    }
                }
            }
        }

        post {
            success {
                echo 'Production deployment completed securely with all environment variables loaded!'
            }
            failure {
                echo 'Deployment failed. Check console output for details.'
            }
        }
    }
}
