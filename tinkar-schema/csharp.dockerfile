FROM mono:latest

# Setting ARG commands
ARG BUILDER_PATH=/home/proto-builder

# Installing NuGet and other required packages
RUN apt-get update && \
    apt-get install -y nuget wget

# Install dotnet for building C# packages
RUN mkdir /root/.dotnet && \
    cd /root/.dotnet && \
    wget --no-verbose https://download.visualstudio.microsoft.com/download/pr/17b6759f-1af0-41bc-ab12-209ba0377779/e8d02195dbf1434b940e0f05ae086453/dotnet-sdk-6.0.100-linux-x64.tar.gz && \
    tar -xf dotnet-sdk-6.0.100-linux-x64.tar.gz && \
    rm -rf dotnet-sdk-6.0.100-linux-x64.tar.gz \

WORKDIR /sln
COPY Tinkar.ProtoBuf-cs.csproj  /sln
COPY Tinkar.proto /sln
COPY LICENSE /sln

CMD ["csharp-generate.sh"]