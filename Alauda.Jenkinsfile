// https://jenkins.io/doc/book/pipeline/syntax/
// Multi-branch discovery pattern: PR-.*
@Library('alauda-cicd') _

// global variables for pipeline
def GIT_BRANCH
def GIT_COMMIT
def FOLDER = "."
def DEBUG = false
def deployment
def RELEASE_VERSION
def RELEASE_BUILD
pipeline {
	// 运行node条件
	// 为了扩容jenkins的功能一般情况会分开一些功能到不同的node上面
	// 这样每个node作用比较清晰，并可以并行处理更多的任务量
	agent { label 'all && java' }

	// (optional) 流水线全局设置
	options {
		// 保留多少流水线记录（建议不放在jenkinsfile里面）
		buildDiscarder(logRotator(numToKeepStr: '10'))

		// 不允许并行执行
		disableConcurrentBuilds()
	}

	//(optional) 环境变量
	environment {
		// for building an scanning
		REPOSITORY = "alauda-devops-sync-plugin"
		OWNER = "alauda"
		// sonar feedback user
		// needs to change together with the credentialsID
		SCM_FEEDBACK_ACCOUNT = "alaudabot"
		SONARQUBE_SCM_CREDENTIALS = "alaudabot"
		DEPLOYMENT = "alauda-devops-sync-plugin"
		DINGDING_BOT = "devops-chat-bot"
		TAG_CREDENTIALS = "alaudabot-github"
		IN_K8S = "true"
	}
	// stages
	stages {
		stage('Checkout') {
			steps {
				script {
					// checkout code
					def scmVars = checkout scm
					// extract git information
					env.GIT_COMMIT = scmVars.GIT_COMMIT
					env.GIT_BRANCH = scmVars.GIT_BRANCH
					GIT_COMMIT = "${scmVars.GIT_COMMIT}"
					GIT_BRANCH = "${scmVars.GIT_BRANCH}"
					pom = readMavenPom file: 'pom.xml'
					//RELEASE_VERSION = pom.properties['revision'] + pom.properties['sha1'] + pom.properties['changelist']
					RELEASE_VERSION = pom.version
				}
				// installing golang coverage and report tools
				sh "go get -u github.com/alauda/gitversion"
				script {
					if (GIT_BRANCH != "master") {
						def branch = GIT_BRANCH.replace("/","-").replace("_","-")
						RELEASE_BUILD = "${RELEASE_VERSION}.${branch}.${env.BUILD_NUMBER}"
					} else {
						sh "gitversion patch ${RELEASE_VERSION} > patch"
						RELEASE_BUILD = readFile("patch").trim()
					}

                    sh '''
					    echo "commit=$GIT_COMMIT" > src/main/resources/debug.properties
                        echo "build=$RELEASE_BUILD" >> src/main/resources/debug.properties
					    echo "version=RELEASE_VERSION" >> src/main/resources/debug.properties
					    cat src/main/resources/debug.properties
                    '''
				}
			}
		}
        stage('Build') {
            when {
                anyOf {
                    changeset '**/**/*.java'
                    changeset '**/**/*.xml'
                    changeset '**/**/*.jelly'
                    changeset '**/**/*.properties'
                    changeset '**/**/*.png'
                }
            }
            steps {
                script {
                    sh """
                        mvn clean install -U findbugs:findbugs -Dmaven.test.skip=true
                    """

                    archiveArtifacts 'target/*.hpi'
                }
            }
        }
		// sonar scan
		stage('Sonar') {
		    when {
                changeset '**/**/*.java'
		    }
			steps {
				script {
					deploy.scan(
						REPOSITORY,
						GIT_BRANCH,
						SONARQUBE_SCM_CREDENTIALS,
						FOLDER,
						DEBUG,
						OWNER,
						SCM_FEEDBACK_ACCOUNT).startToSonar()
				}
			}
		}
	}

	// (optional)
	// happens at the end of the pipeline
	post {
		// 成功
		success {
			echo "Horay!"
			script {
				deploy.notificationSuccess(DEPLOYMENT, DINGDING_BOT, "流水线完成了", RELEASE_BUILD)
			}
		}
		// 失败
		failure {
			// check the npm log
			// fails lets check if it
			script {
			    echo "damn!"
			    deploy.notificationFailed(DEPLOYMENT, DINGDING_BOT, "流水线失败了", RELEASE_BUILD)
			}
		}
		always { junit allowEmptyResults: true, testResults: '**/target/surefire-reports/**/*.xml' }
	}
}

