// ================================================================
// Jenkinsfile — YadinStore CI/CD Demo (repo PUBLICO, free tier)
//
// Este Jenkinsfile replica el job `docker-build` de GitHub Actions
// (ver .github/workflows/ci.yml en este mismo repo). Demuestra el
// mismo pipeline que se usa en el backend real de YadinStore:
//   Checkout -> Compile -> Test -> Package -> Docker Build (validacion)
//
// Puntos de conocimiento Jenkins que demuestra:
//  - Pipeline Declarativo (Jenkinsfile as Code)
//  - Parametros de build (SCM_URL, SCM_BRANCH, etc.)
//  - Options: timestamps() + disableConcurrentBuilds()
//  - Environment blocks y variables de entorno
//  - Stages + dir() para subdirectorios
//  - Publicacion de reportes JUnit (junit step)
//  - Post-build actions (success/failure)
//
// Uso:
//   1. Levantar Jenkins local:  docker compose up -d jenkins
//   2. Password admin inicial en los logs del container.
//   3. Crear job tipo "Pipeline" -> "Pipeline script from SCM"
//      apuntando a: https://github.com/ypmanrique2/yadinstore-cicd-demo.git
//      (repos publico, no requiere credenciales).
// ================================================================

pipeline {
    agent any

    parameters {
        string(name: 'SCM_URL', defaultValue: 'https://github.com/ypmanrique2/yadinstore-cicd-demo.git', description: 'Repo publico del demo (sin credenciales)')
        string(name: 'SCM_BRANCH', defaultValue: 'main', description: 'Rama a construir')
        string(name: 'APP_SUBDIR', defaultValue: 'demo-app', description: 'Subdirectorio de la app (demo-app)')
    }

    options {
        timestamps()
        disableConcurrentBuilds()
    }

    environment {
        DOCKER_IMAGE = 'yadinstore-cicd-demo:jenkins'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout([
                    $class: 'GitSCM',
                    branches: [[name: "${params.SCM_BRANCH}"]],
                    userRemoteConfigs: [[url: "${params.SCM_URL}"]]
                ])
            }
        }

        stage('Compile') {
            steps {
                dir("${params.APP_SUBDIR}") {
                    sh 'mvn -B compile -q'
                }
            }
        }

        stage('Test') {
            steps {
                dir("${params.APP_SUBDIR}") {
                    sh 'mvn -B test -q'
                }
            }
        }

        stage('Package') {
            steps {
                dir("${params.APP_SUBDIR}") {
                    sh 'mvn -B package -DskipTests -q'
                }
            }
            post {
                success {
                    junit '**/target/surefire-reports/*.xml'
                }
            }
        }

        stage('Docker Build') {
            steps {
                dir("${params.APP_SUBDIR}") {
                    // Valida que el Dockerfile compila. Sin push —
                    // solo validacion local del build (igual que Render).
                    sh 'docker build -t ${DOCKER_IMAGE} .'
                }
            }
        }
    }

    post {
        success {
            echo "Build OK: imagen ${DOCKER_IMAGE} generada localmente"
        }
        failure {
            echo 'Build FALLIDO — revisar el log del stage'
        }
    }
}