# tinkar
Monorepo for Tinkar projects forked from IKM Dev

# Setup (Mac)
## Prerequisites
- Java JDK 25 (recommend [Temurin](https://adoptium.net/) via Homebrew `brew install --cask temurin@25`)
- [protoc-gen-doc](https://github.com/pseudomuto/protoc-gen-doc) (`brew install protoc-gen-doc`)
- SNOMED datasource

## Setup
### Build:
1. Under `rocks-kb` run `./mvnw install`
2. Under `tinkar-core` run `./mvnw install`
3. Unzip snomedct-RocksKb and rename folder to `rockskb`
4. Create a new folder `data` under `tinkar-core/service` and move `rockskb` under it (`tinkar-core/service/data/rocksbk`). This datasource path is configurable under `tinkar-core/service/src/main/resources/application.properties`.

Note: On first run, you may need to allow `protoc-gen-doc` in Settings/Privacy & Security

To regenerate auto generated proto files run:

`./mvnw -pl service protobuf:compile protobuf:compile-custom`

### Start server:
Under tinkar-core run:

`./mvnw spring-boot:run -pl service`

Note: If running in IDE, the datasource path may be in a different location (root tinkar-core vs under service) and the --enable-preview flag needs to passed as a VM argument

Default REST port will be on 8085 and gRPC on 9095 (configurable in `application.properties`).

[SwaggerUI URL](http://localhost:8085/swagger-ui/index.html)

Sample gRPC curl:
```
grpcurl -d '{"query":"chronic lung","max_results":200}' \
  localhost:9095 \
  ai.ica.tinkar.TinkarSearchService/ConceptSearch
```
