SAVED_JARS=`find . -name "*grpc*SNAPSHOT.jar"`
mkdir grpc_jar
cp -R $SAVED_JARS grpc_jar
