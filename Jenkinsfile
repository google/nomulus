node {
    stage 'Checkout'
    checkout scm

    stage 'Build'
    sh './gradlew clean build -x test'

    stage 'Test'
    sh './gradlew clean test'

    // Publish the results of the tests
    junit 'build/test-results/*.xml'
}
