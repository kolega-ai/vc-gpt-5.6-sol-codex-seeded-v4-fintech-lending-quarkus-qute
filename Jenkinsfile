pipeline {
  agent any
  tools { jdk 'jdk21' }
  stages {
    stage('Build and test') { steps { sh './gradlew clean test build --no-daemon' } }
    stage('Reports') { steps { junit allowEmptyResults: false, testResults: '**/build/test-results/test/*.xml'; archiveArtifacts artifacts: '**/build/libs/*.jar', fingerprint: true } }
  }
}
