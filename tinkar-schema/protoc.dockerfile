# Base image used by the build system to generate protobuf code

FROM alpine:3.19

# Make and directories that we might need for creating code
RUN mkdir -p /home/proto-builder/src/main/java-generated
VOLUME /home/proto-builder/src/main/java-generated

ARG BUILDER_PATH=/home/proto-builder
ARG PROTOC_VERSION=30.2
WORKDIR $BUILDER_PATH

# Download and install protoc 4.30.2 (protoc version uses different numbering - 30.2 = 4.30.2)
RUN apk update && \
    apk add wget unzip gcompat && \
    wget https://github.com/protocolbuffers/protobuf/releases/download/v${PROTOC_VERSION}/protoc-${PROTOC_VERSION}-linux-x86_64.zip && \
    unzip protoc-${PROTOC_VERSION}-linux-x86_64.zip -d /usr/local && \
    rm protoc-${PROTOC_VERSION}-linux-x86_64.zip && \
    chmod +x /usr/local/bin/protoc

COPY Tinkar.proto .
COPY java-generate.sh .

CMD ["sh", "./java-generate.sh"]
