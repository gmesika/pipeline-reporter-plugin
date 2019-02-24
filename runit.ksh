export JAVA_HOME=/home/ubuntu1/jdk1.8.0_191/
mvn clean
export MAVEN_OPTS="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,address=8000,suspend=n"
mvn hpi:run
