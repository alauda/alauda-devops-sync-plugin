build:
	rm -rf ~/.m2/repository/io/alauda/
	mvn clean install -DskipTests
