mkdir -p /home/proto-builder/src/main/java-generated
protoc -I /home/proto-builder/ /home/proto-builder/Tinkar.proto --java_out=/home/proto-builder/src/main/java-generated
pwd
ls -R src/main/java-generated