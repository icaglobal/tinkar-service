mkdir -p /home/proto-builder/src/main/java
protoc -I /home/proto-builder/ /home/proto-builder/Tinkar.proto --java_out=/home/proto-builder/src/main/java
pwd
ls -R src/main/java
