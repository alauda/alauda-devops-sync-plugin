install:
	rm -rf ~/.m2/repository/io/alauda/
	mvn clean install -DskipTests

package:
	mvn clean package -DskipTests

restart:
	./scripts/restart.sh

run:
	./scripts/run.sh

upload:
	./scripts/upload.sh