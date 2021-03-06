package com.thinkaurelius.titan.graphdb;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.thinkaurelius.titan.core.TitanException;
import com.thinkaurelius.titan.diskstorage.BackendException;
import com.thinkaurelius.titan.diskstorage.StaticBuffer;
import com.thinkaurelius.titan.diskstorage.TemporaryBackendException;
import com.thinkaurelius.titan.diskstorage.configuration.ConfigOption;
import com.thinkaurelius.titan.diskstorage.configuration.Configuration;
import com.thinkaurelius.titan.diskstorage.log.*;
import com.thinkaurelius.titan.diskstorage.log.util.FutureMessage;
import com.thinkaurelius.titan.diskstorage.util.time.Timepoint;
import com.thinkaurelius.titan.diskstorage.util.time.TimestampProvider;

import static com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * @author Matthias Broecheler (me@matthiasb.com)
 */
public class TestMockLog implements LogManager {

    public static final ConfigOption<Boolean> LOG_MOCK_FAILADD = new ConfigOption<Boolean>(LOG_NS,"fail-adds",
            "Sets the log to reject adding messages. FOR TESTING ONLY",
            ConfigOption.Type.LOCAL, false).hide();

    private final Map<String,TestLog> openLogs = Maps.newHashMap();
    private final boolean failAdds;
    private final String senderId;
    private final TimestampProvider times;

    public TestMockLog(Configuration config) {
        this.failAdds = config.get(LOG_MOCK_FAILADD);
        this.senderId = config.get(UNIQUE_INSTANCE_ID);
        this.times = config.get(TIMESTAMP_PROVIDER);
    }

    @Override
    public synchronized Log openLog(String name) throws BackendException {
        TestLog log = openLogs.get(name);
        if (log==null) {
            log = new TestLog(name);
            openLogs.put(name,log);
        }
        return log;
    }

    @Override
    public synchronized void close() throws BackendException {
        openLogs.clear();
    }


    private class TestLog implements Log {

        private final String name;
        private final Set<MessageReader> readers =Sets.newHashSet();
        private List<FutureMessage<TestMessage>> messageBacklog = Lists.newArrayList();

        private TestLog(String name) {
            this.name = name;
        }

        @Override
        public synchronized Future<Message> add(StaticBuffer content) {
            TestMessage msg = new TestMessage(content);
            FutureMessage<TestMessage> fmsg = new FutureMessage<TestMessage>(msg);

            if (failAdds) {
                System.out.println("Failed message add");
                throw new TitanException("Log unavailable");
            }

            if (readers.isEmpty()) {
                messageBacklog.add(fmsg);
            } else {
                process(fmsg);
            }
            return fmsg;
        }

        private void process(FutureMessage<TestMessage> fmsg) {
            for (MessageReader reader : readers) {
                reader.read(fmsg.getMessage());
            }
            fmsg.delivered();
        }

        @Override
        public synchronized Future<Message> add(StaticBuffer content, StaticBuffer key) {
            return add(content);
        }

        @Override
        public synchronized void registerReader(ReadMarker readMarker, MessageReader... reader) {
            registerReaders(readMarker, Arrays.asList(reader));
        }

        @Override
        public synchronized void registerReaders(ReadMarker readMarker, Iterable<MessageReader> readers) {
            for (FutureMessage<TestMessage> fmsg : messageBacklog) {
                process(fmsg);
            }
            messageBacklog=null;
            Iterables.addAll(this.readers,readers);
        }

        @Override
        public synchronized boolean unregisterReader(MessageReader reader) {
            return readers.remove(reader);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void close() throws BackendException {
            readers.clear();
        }
    }

    private class TestMessage implements Message {

        private final Timepoint time;
        private final StaticBuffer content;

        private TestMessage(StaticBuffer content) {
            this.time = times.getTime();
            this.content = content;
        }

        @Override
        public String getSenderId() {
            return senderId;
        }

        @Override
        public long getTimestamp(TimeUnit unit) {
            return time.getTimestamp(unit);
        }

        @Override
        public StaticBuffer getContent() {
            return content;
        }
    }


}
