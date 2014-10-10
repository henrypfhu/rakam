package org.rakam.collection;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.google.inject.Binding;
import com.google.inject.TypeLiteral;
import org.apache.log4j.Logger;
import org.rakam.ServiceStarter;
import org.rakam.cache.DistributedAnalysisRuleMap;
import org.rakam.cache.DistributedCacheAdapter;
import org.rakam.cache.local.LocalCacheAdapter;
import org.rakam.database.DatabaseAdapter;
import org.rakam.plugin.CollectionMapperPlugin;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.platform.Verticle;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.rakam.util.DateUtil.UTCTime;

/**
 * Created by buremba on 21/05/14.
 */
public class CollectionWorker extends Verticle implements Handler<Message<byte[]>> {
    final ExecutorService executor = new ThreadPoolExecutor(5, 50, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue());

    public LocalCacheAdapter localStorageAdapter = new LocalCacheAdapter();
    private DistributedCacheAdapter cacheAdapter = ServiceStarter.injector.getInstance(DistributedCacheAdapter.class);
    private DatabaseAdapter databaseAdapter = ServiceStarter.injector.getInstance(DatabaseAdapter.class);

    public static final String collectEvent = "collectEvent";
    public static final String collectActor = "collectActor";

    final private EventAggregator eventAggregator = new EventAggregator(localStorageAdapter, cacheAdapter, databaseAdapter);

    static Kryo kryo = new Kryo();
    static Logger logger = Logger.getLogger(CollectionWorker.class.getName());
    static List<CollectionMapperPlugin> mappers = new LinkedList();
    static {
        List<Binding<CollectionMapperPlugin>> bindingsByType = ServiceStarter.injector.findBindingsByType(new TypeLiteral<CollectionMapperPlugin>() {});
        mappers.addAll(bindingsByType.stream().map(mapper -> mapper.getProvider().get()).collect(Collectors.toList()));
        kryo.register(JsonObject.class);
    }

    private long collectorTimerId;

    public void start() {
        vertx.eventBus().registerLocalHandler(collectEvent, this);
        vertx.eventBus().registerLocalHandler(collectActor, this);

        final PeriodicCollector periodicCollector = new PeriodicCollector(localStorageAdapter, cacheAdapter, databaseAdapter);

        collectorTimerId = vertx.setPeriodic(2000, timerID -> {
            //long first = System.currentTimeMillis();
            DistributedAnalysisRuleMap.entrySet().forEach(periodicCollector::process);
            //System.out.print(" "+(System.currentTimeMillis() - first)+" ");
        });
    }

    @Override
    public void handle(final Message<byte[]> data) {
        if(data.address().equals(collectEvent)) {
            executor.submit(createEventCollectionTask(data));
        }else
        if(data.address().equals(collectActor)) {
            executor.submit(createActorCollectionTask(data));
        }else {
            logger.error("unknown collection type");
            data.reply("0".getBytes());
        }
    }

    private Runnable createEventCollectionTask(final Message<byte[]> data) {
        return () -> {
            try {
                final JsonObject message = kryo.readObject(new Input(data.body()), JsonObject.class);

                mappers.stream().filter(mapper -> !mapper.map(message)).forEach(mapper -> data.reply("0".getBytes()));

                String project = message.getString("_tracker");
                String actor_id = message.getString("_user");

                Integer time = message.getInteger("_time");
                if(time==null) {
                    time = UTCTime();
                }


                Iterator<String> it = message.getFieldNames().iterator();
                while(it.hasNext()) {
                    String key = it.next();
                    if (key.startsWith("_")) {
                        it.remove();
                    }

                }


                databaseAdapter.addEvent(project, actor_id, message);

                eventAggregator.aggregate(project, message, actor_id, time);

                data.reply("1".getBytes());
            } catch (Exception e) {
                data.reply("0".getBytes());
                logger.error("error while processing event collection request", e);
            }
        };
    }

    public Runnable createActorCollectionTask(final Message<byte[]> data) {
        return () -> {
            try {
                final JsonObject message = kryo.readObject(new Input(data.body()), JsonObject.class);

                String tracker = message.getString("tracker");
                String actor_id = message.getString("user");

                JsonObject attributes = message.getObject("attrs");
                databaseAdapter.addPropertyToActor(tracker, actor_id, attributes.toMap());
                data.reply("1".getBytes());
            } catch (Exception e) {
                data.reply("0".getBytes());
                logger.error("error while processing event collection request", e);
            }
        };
    }

    public void stop() {
        vertx.cancelTimer(collectorTimerId);
    }

}
