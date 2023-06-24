package com.thecodinginterface.kinesis;

import com.amazonaws.services.kinesis.producer.KinesisProducer;
import com.amazonaws.services.kinesis.producer.KinesisProducerConfiguration;
import com.amazonaws.services.kinesis.producer.UserRecordResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Future;

public class ProducerApp {
    static final Logger logger = LogManager.getLogger(ProducerApp.class);
    static final ObjectMapper objMapper = new ObjectMapper();

    public static void main(String[] args) {
        String streamName = args[0];
        String region = args[1];
        logger.info(String.format("Starting Kinesis Producer Library Application for Stream %s in %s", streamName, region));

        var producerConfig = new KinesisProducerConfiguration().setRegion(region);

        // To disable aggregation so other non-KCL consumers can consume these events
        if (args.length == 3 && args[2].equalsIgnoreCase("--no-agg")) {
            logger.info("Disabling aggregation");
            producerConfig.setAggregationEnabled(false);
        }

        // instantiate KPL client
        var producer = new KinesisProducer(producerConfig);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down program");
            producer.flush();
        }, "producer-shutdown"));

        List<Future<UserRecordResult>> putFutures = new LinkedList<Future<UserRecordResult>>();

        // continuously produce records
        int i = 0;
        while(true) {
            // Generate fake order item data
            var order = OrderGenerator.makeOrder();
            logger.info(String.format("Generated %s", order));

            // serialize and add to producer to be batched and sent to Kinesis
            ByteBuffer data = null;
            try {
                data = ByteBuffer.wrap(objMapper.writeValueAsBytes(order));
            } catch (JsonProcessingException e) {
                logger.error(String.format("Failed to serialize %s", order), e);
                continue;
            }
            ListenableFuture<UserRecordResult> future = producer.addUserRecord(
                    streamName,
                    order.getOrderID(),
                    data
            );
            putFutures.add(future);

            // register a callback handler on the async Future
            Futures.addCallback(future, new FutureCallback<UserRecordResult>() {
                @Override
                public void onFailure(Throwable t) {
                    logger.error("Failed to produce batch", t);
                }

                @Override
                public void onSuccess(UserRecordResult result) {
                    logger.info(String.format("Produced User Record to shard %s at position %s",
                                        result.getShardId(),
                                        result.getSequenceNumber()));
                }
            }, MoreExecutors.directExecutor());

            // introduce artificial delay for demonstration purpose / visual tracking of logging
            if (++i % 100 == 0) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    logger.warn("Sleep interrupted", e);
                }
            }
        }
    }
}
