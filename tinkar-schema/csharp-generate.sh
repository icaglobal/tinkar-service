mkdir -p $(pwd)/src/main/csharp-generated
protoc -I $(pwd) $(pwd)/Tinkar.proto --csharp_out=$(pwd)/src/main/csharp-generated