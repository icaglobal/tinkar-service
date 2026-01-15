# Base image used by the build system to generate protobuf code

FROM alpine:3.17.2

# Make and directories that we might need for creating code
RUN mkdir -p /home/proto-builder/src/main/java-generated
VOLUME /home/proto-builder/src/main/java-generated

ARG BUILDER_PATH=/home/proto-builder
WORKDIR $BUILDER_PATH

# Installing protoc using apt-install and generating the correct directories for the stored generated classes.
RUN apk update && \
    apk add protobuf=3.21.9-r0 protobuf-dev=3.21.9-r0 wget unzip

COPY Tinkar.proto .
COPY java-generate.sh .

CMD ["sh", "./java-generate.sh"]