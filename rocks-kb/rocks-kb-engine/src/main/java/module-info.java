import dev.ikm.ds.rocks.RocksProvider;
import dev.ikm.tinkar.common.service.DataServiceController;
import dev.ikm.tinkar.common.service.ExecutorController;
import dev.ikm.tinkar.common.service.LoadDataFromFileController;
import dev.ikm.tinkar.common.service.ServiceLifecycle;
import dev.ikm.tinkar.entity.ChangeSetWriterService;
import dev.ikm.tinkar.entity.EntityService;

module dev.ikm.rocks.engine {
    exports dev.ikm.ds.rocks;
    exports dev.ikm.ds.rocks.spliterator;
    exports dev.ikm.ds.rocks.tasks;
    requires dev.ikm.jpms.activej.bytebuf;
    requires dev.ikm.jpms.protobuf;
    requires dev.ikm.rocksdb.jpms;
    requires dev.ikm.tinkar.common;
    requires dev.ikm.tinkar.component;
    requires dev.ikm.tinkar.entity;
    requires dev.ikm.tinkar.provider.search;
    requires dev.ikm.tinkar.schema;
    requires dev.ikm.tinkar.terms;
    requires org.eclipse.collections.api;
    requires org.slf4j;
    requires org.eclipse.collections.impl;

    provides DataServiceController
            with RocksProvider.OpenController, RocksProvider.NewController;
    provides ServiceLifecycle
            with RocksProvider.OpenController, RocksProvider.NewController;

    uses LoadDataFromFileController;
    uses ChangeSetWriterService;
    uses ExecutorController;
    uses EntityService;

}