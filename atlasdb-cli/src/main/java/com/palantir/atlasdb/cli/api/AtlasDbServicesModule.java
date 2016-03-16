package com.palantir.atlasdb.cli.api;

import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.transaction.impl.SerializableTransactionManager;
import com.palantir.lock.RemoteLockService;
import com.palantir.timestamp.TimestampService;

import dagger.Module;
import dagger.Provides;

@Module
public class AtlasDbServicesModule {

    @Provides
    TimestampService provideTimestampService() {
        return null;
    }

    @Provides
    RemoteLockService provideRemoteLockService() {
        return null;
    }

    @Provides
    KeyValueService provideKeyValueService() {
        return null;
    }

    @Provides
    SerializableTransactionManager provideTransactionManager() {
        return null;
    }

}
