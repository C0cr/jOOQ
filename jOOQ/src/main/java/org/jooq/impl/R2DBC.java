/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Other licenses:
 * -----------------------------------------------------------------------------
 * Commercial licenses for this work are available. These replace the above
 * ASL 2.0 and offer limited warranties, support, maintenance, and commercial
 * database integrations.
 *
 * For more information, please visit: http://www.jooq.org/licenses
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */
package org.jooq.impl;

// ...
import static org.jooq.conf.ParamType.NAMED;
import static org.jooq.impl.Internal.subscriber;
import static org.jooq.impl.Tools.EMPTY_FIELD;
import static org.jooq.impl.Tools.EMPTY_PARAM;
import static org.jooq.impl.Tools.abstractDMLQuery;
import static org.jooq.impl.Tools.abstractResultQuery;
import static org.jooq.impl.Tools.fieldNameStrings;
import static org.jooq.impl.Tools.fields;
import static org.jooq.impl.Tools.recordFactory;
import static org.jooq.impl.Tools.visitAll;
import static org.jooq.tools.StringUtils.defaultIfNull;
import static org.jooq.tools.jdbc.JDBCUtils.safeClose;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLType;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jooq.BindingGetResultSetContext;
import org.jooq.Configuration;
import org.jooq.Cursor;
import org.jooq.DataType;
import org.jooq.Field;
import org.jooq.Param;
// ...
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.conf.SettingsTools;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DefaultRenderContext.Rendered;
import org.jooq.impl.Tools.ThreadGuard;
import org.jooq.impl.Tools.ThreadGuard.Guard;
import org.jooq.tools.Convert;
import org.jooq.tools.JooqLogger;
import org.jooq.tools.jdbc.DefaultPreparedStatement;
import org.jooq.tools.jdbc.DefaultResultSet;
import org.jooq.tools.jdbc.MockArray;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import io.r2dbc.spi.Batch;
import io.r2dbc.spi.ColumnMetadata;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import io.r2dbc.spi.Statement;

/**
 * A single namespace for all reactive {@link Subscription} and other
 * implementations.
 */
final class R2DBC {

    private static final JooqLogger log    = JooqLogger.getLogger(R2DBC.class);
    static volatile boolean         is_0_9 = true;

    // -------------------------------------------------------------------------
    // Utilities to pass the TCK
    // -------------------------------------------------------------------------

    static abstract class AbstractSubscription<T> implements Subscription {

        final AtomicBoolean         completed;
        final AtomicLong            requested;
        final Subscriber<? super T> subscriber;
        final Guard                 guard;

        static <T> Subscription onRequest(Subscriber<? super T> s, Consumer<? super Subscriber<? super T>> onRequest) {
            return new AbstractSubscription<T>(s) {
                @Override
                void request0() {
                    onRequest.accept(subscriber);
                }
            };
        }

        AbstractSubscription(Subscriber<? super T> subscriber) {
            this.completed = new AtomicBoolean();
            this.requested = new AtomicLong();
            this.guard = new Guard();
            this.subscriber = subscriber(
                subscriber::onSubscribe,
                subscriber::onNext,
                subscriber::onError,
                () -> {

                    // required_spec107_mustNotEmitFurtherSignalsOnceOnCompleteHasBeenSignalled
                    // required_spec302_mustAllowSynchronousRequestCallsFromOnNextAndOnSubscribe
                    // required_spec317_mustSupportACumulativePendingElementCountUpToLongMaxValue
                    completed.set(true);
                    subscriber.onComplete();
                }
            );
        }

        @Override
        public final void request(long n) {
            if (n <= 0) {
                subscriber.onError(new IllegalArgumentException("Rule 3.9 non-positive request signals are illegal"));
            }
            else if (!completed.get()) {
                requested.accumulateAndGet(n, R2DBC::addNoOverflow);

                // required_spec303_mustNotAllowUnboundedRecursion (this assumes subscriber loops on requested)
                ThreadGuard.run(guard, this::request0, () -> {});
            }
        }

        @Override
        public final void cancel() {
            complete(true);
        }

        final boolean moreRequested() {

            // required_spec312_cancelMustMakeThePublisherToEventuallyStopSignaling
            return !completed.get() && requested.getAndUpdate(l -> l == Long.MAX_VALUE ? l : Math.max(0, l - 1)) > 0;
        }

        final void complete(boolean cancelled) {

            // required_spec306_afterSubscriptionIsCancelledRequestMustBeNops
            // required_spec307_afterSubscriptionIsCancelledAdditionalCancelationsMustBeNops
            if (!completed.getAndSet(true))
                cancel0(cancelled);
        }

        abstract void request0();
        void cancel0(boolean cancelled) {}
    }

    // -------------------------------------------------------------------------
    // R2DBC implementations
    // -------------------------------------------------------------------------

    static final class Forwarding<T> implements Subscriber<T> {

        final int                           forwarderIndex;
        final AbstractResultSubscriber<T>   resultSubscriber;
        final AtomicReference<Subscription> subscription;

        Forwarding(int forwarderIndex, AbstractResultSubscriber<T> resultSubscriber) {
            this.forwarderIndex = forwarderIndex;
            this.resultSubscriber = resultSubscriber;
            this.subscription = new AtomicReference<>();
        }

        @Override
        public final void onSubscribe(Subscription s) {
            subscription.set(s);
            resultSubscriber.downstream.request1(s);
        }

        @Override
        public final void onNext(T value) {
            if (!resultSubscriber.downstream.completed.get()) {
                resultSubscriber.downstream.subscriber.onNext(value);
                resultSubscriber.downstream.request1(subscription.get());
            }
        }

        @Override
        public final void onError(Throwable t) {
            resultSubscriber.downstream.subscriber.onError(t);
        }

        @Override
        public final void onComplete() {
            resultSubscriber.downstream.forwarders.remove(forwarderIndex);
            resultSubscriber.complete();
        }
    }

    static abstract class AbstractResultSubscriber<T> implements Subscriber<Result> {

        final AbstractNonBlockingSubscription<? super T> downstream;
        final AtomicBoolean                              completed;

        AbstractResultSubscriber(AbstractNonBlockingSubscription<? super T> downstream) {
            this.downstream = downstream;
            this.completed = new AtomicBoolean();
        }

        @Override
        public final void onSubscribe(Subscription s) {
            s.request(Long.MAX_VALUE);
        }

        @Override
        public final void onError(Throwable t) {
            downstream.subscriber.onError(t);
        }

        @Override
        public final void onComplete() {
            completed.set(true);
            complete();
        }

        final void complete() {
            if (completed.get() && downstream.forwarders.isEmpty())
                downstream.complete(false);
        }
    }

    static final class RowCountSubscriber extends AbstractResultSubscriber<Integer> {

        RowCountSubscriber(AbstractNonBlockingSubscription<? super Integer> downstream) {
            super(downstream);
        }

        @Override
        public void onNext(Result r) {
            r.getRowsUpdated().subscribe(downstream.forwardingSubscriber((AbstractResultSubscriber) this));
        }
    }

    static final class ResultSubscriber<R extends Record, Q extends ResultQueryTrait<R>> extends AbstractResultSubscriber<R> {

        final Q query;

        ResultSubscriber(Q query, AbstractNonBlockingSubscription<? super R> downstream) {
            super(downstream);

            this.query = query;
        }

        @SuppressWarnings("unchecked")
        @Override
        public final void onNext(Result r) {
            r.map((row, m) -> {
                try {
                    // TODO: Cache this getFields() call
                    Field<?>[] fields = query.getFields(new R2DBCResultSetMetaData(query.configuration(), m));

                    // TODO: This call is duplicated from CursorImpl and related classes.
                    // Refactor this call to make sure code is re-used, especially when
                    // ExecuteListener lifecycle management is implemented
                    RecordDelegate<AbstractRecord> delegate = Tools.newRecord(true, (Supplier<AbstractRecord>) recordFactory(query.getRecordType(), Tools.row0(fields)), query.configuration());

                    return (R) delegate.operate(record -> {

                        // TODO: What data to pass here?
                        DefaultBindingGetResultSetContext<?> ctx = new DefaultBindingGetResultSetContext<>(
                            query.configuration(),
                            query.configuration().data(),
                            new R2DBCResultSet(query.configuration(), row),
                            0
                        );

                        // TODO: Make sure all the embeddable records, and other types of nested records are supported
                        for (int i = 0; i < fields.length; i++) {
                            ctx.index(i + 1);
                            fields[i].getBinding().get((BindingGetResultSetContext) ctx);
                            record.values[i] = ctx.value();
                            record.originals[i] = ctx.value();
                        }

                        return record;
                    });
                }

                // TODO: More specific error handling
                catch (Throwable t) {
                    onError(t);
                    return null;
                }
            }).subscribe(downstream.forwardingSubscriber((AbstractResultSubscriber) this));
        }
    }

    static abstract class ConnectionSubscriber<T> implements Subscriber<Connection> {

        final AbstractNonBlockingSubscription<T> downstream;
        final AtomicReference<Connection>        connection;

        ConnectionSubscriber(AbstractNonBlockingSubscription<T> downstream) {
            this.downstream = downstream;
            this.connection = new AtomicReference<>();
        }

        @Override
        public final void onSubscribe(Subscription s) {
            s.request(1);
        }

        @Override
        public final void onNext(Connection c) {
            connection.set(c);
            onNext0(c);
        }

        abstract void onNext0(Connection c);

        @Override
        public final void onError(Throwable t) {
            downstream.subscriber.onError(t);
        }

        @Override
        public final void onComplete() {}
    }

    static final class QueryExecutionSubscriber<T, Q extends Query> extends ConnectionSubscriber<T> {

        final Q                                                                     query;
        final Configuration                                                         configuration;
        final BiFunction<Q, AbstractNonBlockingSubscription<T>, Subscriber<Result>> resultSubscriber;

        QueryExecutionSubscriber(
            Q query,
            QuerySubscription<T, Q> downstream,
            BiFunction<Q, AbstractNonBlockingSubscription<T>, Subscriber<Result>> resultSubscriber
        ) {
            super(downstream);

            this.query = query;
            this.configuration = query.configuration();
            this.resultSubscriber = resultSubscriber;
        }

        @Override
        final void onNext0(Connection c) {
            try {
                Rendered rendered = rendered(configuration, query);
                Statement stmt = c.createStatement(rendered.sql);
                new DefaultBindContext(configuration, new R2DBCPreparedStatement(configuration, stmt)).visit(rendered.bindValues);

                // TODO: Reuse org.jooq.impl.Tools.setFetchSize(ExecuteContext ctx, int fetchSize)
                AbstractResultQuery<?> q1 = abstractResultQuery(query);
                if (q1 != null) {
                    int f = SettingsTools.getFetchSize(q1.fetchSize(), configuration.settings());

                    if (f != 0) {
                        if (log.isDebugEnabled())
                            log.debug("Setting fetch size", f);

                        stmt.fetchSize(f);
                    }
                }

                AbstractDMLQuery<?> q2 = abstractDMLQuery(query);
                if (q2 != null
                        && !q2.returning.isEmpty()



                        && !q2.nativeSupportReturning(configuration.dsl()))
                    stmt.returnGeneratedValues(fieldNameStrings(q2.returningResolvedAsterisks.toArray(EMPTY_FIELD)));

                stmt.execute().subscribe(resultSubscriber.apply(query, downstream));
            }

            // TODO: More specific error handling
            catch (Throwable t) {
                onError(t);
            }
        }
    }

    static final class BatchMultipleSubscriber extends ConnectionSubscriber<Integer> {

        final BatchMultiple batch;

        BatchMultipleSubscriber(
            BatchMultiple batch,
            BatchSubscription<BatchMultiple> downstream
        ) {
            super(downstream);

            this.batch = batch;
        }

        @Override
        final void onNext0(Connection c) {
            try {
                Batch b = c.createBatch();

                for (int i = 0; i < batch.queries.length; i++)
                    b = b.add(DSL.using(batch.configuration).renderInlined(batch.queries[i]));

                b.execute().subscribe(new RowCountSubscriber(downstream));
            }

            // TODO: More specific error handling
            catch (Throwable t) {
                onError(t);
            }
        }
    }

    static final class BatchSingleSubscriber extends ConnectionSubscriber<Integer> {

        final BatchSingle batch;

        BatchSingleSubscriber(
            BatchSingle batch,
            BatchSubscription<BatchSingle> downstream
        ) {
            super(downstream);

            this.batch = batch;
        }

        @Override
        final void onNext0(Connection c) {
            try {
                batch.checkBindValues();
                Rendered rendered = rendered(batch.configuration, batch.query);
                Statement stmt = c.createStatement(rendered.sql);
                Param<?>[] params = rendered.bindValues.toArray(EMPTY_PARAM);

                for (Object[] bindValues : batch.allBindValues) {

                    // [#1371] [#2139] Don't bind variables directly onto statement, bind them through the collected params
                    //                 list to preserve type information
                    // [#3547]         The original query may have no Params specified - e.g. when it was constructed with
                    //                 plain SQL. In that case, infer the bind value type directly from the bind value
                    visitAll(new DefaultBindContext(batch.configuration, new R2DBCPreparedStatement(batch.query.configuration(), stmt)),
                        (params.length > 0)
                            ? fields(bindValues, params)
                            : fields(bindValues));

                    stmt = stmt.add();
                }

                stmt.execute().subscribe(new RowCountSubscriber(downstream));
            }

            // TODO: More specific error handling
            catch (Throwable t) {
                onError(t);
            }
        }
    }

    static abstract class AbstractNonBlockingSubscription<T> extends AbstractSubscription<T> {

        final AtomicBoolean                         subscribed;
        final Publisher<? extends Connection>       connection;
        final AtomicInteger                         nextForwarderIndex;
        final ConcurrentMap<Integer, Forwarding<T>> forwarders;

        AbstractNonBlockingSubscription(
            Configuration configuration,
            Subscriber<? super T> subscriber
        ) {
            super(subscriber);

            this.subscribed = new AtomicBoolean();
            this.connection = configuration.connectionFactory().create();
            this.nextForwarderIndex = new AtomicInteger();
            this.forwarders = new ConcurrentHashMap<>();
        }

        @Override
        final void request0() {

            // Lazy execution of the query
            if (!subscribed.getAndSet(true))
                connection.subscribe(delegate());

            // Forwarders all forward to the same downstream and are not
            // expected to be contained in the map at the same time.
            for (Forwarding<T> f : forwarders.values()) {
                Subscription s = f.subscription.get();

                if (s != null)
                    request1(s);
            }
        }

        final void request1(Subscription s) {
            if (moreRequested())
                s.request(1);
        }

        @Override
        final void cancel0(boolean cancelled) {
            delegate().connection.updateAndGet(c -> {

                // close() calls on already closed resources have no effect, so
                // the side-effect is OK with the AtomicReference contract
                if (c != null)
                    c.close().subscribe(subscriber(s -> s.request(Long.MAX_VALUE), t -> {}, t -> {}, () -> {}));

                return null;
            });

            if (!cancelled)
                subscriber.onComplete();
        }

        abstract ConnectionSubscriber<T> delegate();

        final Forwarding<T> forwardingSubscriber(AbstractResultSubscriber<T> resultSubscriber) {
            int i = nextForwarderIndex.getAndIncrement();
            Forwarding<T> f = new Forwarding<>(i, resultSubscriber);
            forwarders.put(i, f);
            return f;
        }
    }

    static final class QuerySubscription<T, Q extends Query> extends AbstractNonBlockingSubscription<T> {

        final QueryExecutionSubscriber<T, Q> queryExecutionSubscriber;

        QuerySubscription(
            Q query,
            Subscriber<? super T> subscriber,
            BiFunction<Q, AbstractNonBlockingSubscription<T>, Subscriber<Result>> resultSubscriber
        ) {
            super(query.configuration(), subscriber);

            this.queryExecutionSubscriber = new QueryExecutionSubscriber<>(query, this, resultSubscriber);
        }

        @Override
        final QueryExecutionSubscriber<T, Q> delegate() {
            return queryExecutionSubscriber;
        }
    }

    static final class BatchSubscription<B extends AbstractBatch> extends AbstractNonBlockingSubscription<Integer> {

        final ConnectionSubscriber<Integer> batchSubscriber;

        BatchSubscription(
            B batch,
            Subscriber<? super Integer> subscriber,
            Function<BatchSubscription<B>, ConnectionSubscriber<Integer>> batchSubscriber
        ) {
            super(batch.configuration, subscriber);

            this.batchSubscriber = batchSubscriber.apply(this);
        }

        @Override
        final ConnectionSubscriber<Integer> delegate() {
            return batchSubscriber;
        }
    }

    // -------------------------------------------------------------------------
    // Internal R2DBC specific utilities
    // -------------------------------------------------------------------------

    static final Rendered rendered(Configuration configuration, Query query) {
        DefaultRenderContext render = new DefaultRenderContext(configuration.derive(
            setParamType(configuration.dialect(), configuration.settings())
        ));

        return new Rendered(render.paramType(NAMED).visit(query).render(), render.bindValues(), render.skipUpdateCounts());
    }

    static final long addNoOverflow(long x, long y) {
        long r = x + y;

        // See Long::addExact
        if (((x ^ r) & (y ^ r)) < 0)
            return Long.MAX_VALUE;
        else
            return r;
    }

    @SuppressWarnings("unchecked")
    static final <T> T block(Publisher<? extends T> publisher) {
        Object complete = new Object();
        LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        publisher.subscribe(subscriber(s -> s.request(1), queue::add, queue::add, () -> queue.add(complete)));

        try {
            Object result = queue.take();

            if (result instanceof Throwable)
                throw new DataAccessException("Exception when blocking on publisher", (Throwable) result);
            else if (result == complete)
                return null;
            else
                return (T) result;
        }
        catch (InterruptedException e) {
            throw new DataAccessException("Exception when blocking on publisher", e);
        }
    }

    static final Connection getConnection(String url) {
        return block(ConnectionFactories.get(url).create());
    }

    static final Connection getConnection(String url, String username, String password) {
        return block(ConnectionFactories.get(
            ConnectionFactoryOptions
                .parse(url)
                .mutate()
                .option(ConnectionFactoryOptions.USER, username)
                .option(ConnectionFactoryOptions.PASSWORD, password)
                .build()
        ).create());
    }

    // -------------------------------------------------------------------------
    // JDBC to R2DBC bridges for better interop, where it doesn't matter
    // -------------------------------------------------------------------------

    static final class R2DBCPreparedStatement extends DefaultPreparedStatement {

        final Configuration c;
        final Statement     s;

        R2DBCPreparedStatement(Configuration c, Statement s) {
            super(null, null, () -> new SQLFeatureNotSupportedException("Unsupported operation of the JDBC to R2DBC bridge."));

            this.c = c;
            this.s = s;
        }

        private final void bindNonNull(int parameterIndex, Object x) {
            switch (c.family()) {







                default:
                    s.bind(parameterIndex - 1, x);
                    break;
            }
        }

        private final <T> void bindNull(int parameterIndex, Class<T> type) {
            switch (c.family()) {







                default:
                    s.bindNull(parameterIndex - 1, type);
                    break;
            }
        }

        private final <T> void bindNullable(int parameterIndex, T x, Class<T> type) {
            bindNullable(parameterIndex, x, type, t -> t);
        }

        private final <T, U> void bindNullable(int parameterIndex, T x, Class<U> type, Function<? super T, ? extends U> conversion) {
            if (x == null)
                bindNull(parameterIndex, type);
            else
                bindNonNull(parameterIndex, conversion.apply(x));
        }

        private final Class<?> type(int sqlType) {

            // [#11700] Intercept JDBC temporal types, which aren't supported by R2DBC
            switch (sqlType) {
                case Types.DATE:
                    return LocalDate.class;
                case Types.TIME:
                    return LocalTime.class;
                case Types.TIMESTAMP:
                    return LocalDateTime.class;
                default:
                    return DefaultDataType.getDataType(c.family(), sqlType).getType();
            }
        }

        private final Class<?> type(Class<?> type) {

            // [#11700] Intercept JDBC temporal types, which aren't supported by R2DBC
            if (type == Date.class)
                return LocalDate.class;
            else if (type == Time.class)
                return LocalTime.class;
            else if (type == Timestamp.class)
                return LocalDateTime.class;
            else
                return type;
        }

        @Override
        public final void setNull(int parameterIndex, int sqlType) throws SQLException {
            bindNull(parameterIndex, type(sqlType));
        }

        @Override
        public final void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
            bindNull(parameterIndex, type(sqlType));
        }

        public final void setNull(int parameterIndex, DataType<?> dataType) {
            bindNull(parameterIndex, type(dataType.getType()));
        }

        @Override
        public final void setBoolean(int parameterIndex, boolean x) throws SQLException {
            bindNonNull(parameterIndex, x);
        }

        @Override
        public final void setByte(int parameterIndex, byte x) throws SQLException {
            bindNonNull(parameterIndex, x);
        }

        @Override
        public final void setShort(int parameterIndex, short x) throws SQLException {
            bindNonNull(parameterIndex, x);
        }

        @Override
        public final void setInt(int parameterIndex, int x) throws SQLException {
            bindNonNull(parameterIndex, x);
        }

        @Override
        public final void setLong(int parameterIndex, long x) throws SQLException {
            bindNonNull(parameterIndex, x);
        }

        @Override
        public final void setFloat(int parameterIndex, float x) throws SQLException {
            bindNonNull(parameterIndex, x);
        }

        @Override
        public final void setDouble(int parameterIndex, double x) throws SQLException {
            bindNonNull(parameterIndex, x);
        }

        @Override
        public final void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
            bindNullable(parameterIndex, x, BigDecimal.class);
        }

        @Override
        public final void setString(int parameterIndex, String x) throws SQLException {
            bindNullable(parameterIndex, x, String.class);
        }

        @Override
        public final void setNString(int parameterIndex, String value) throws SQLException {
            bindNullable(parameterIndex, value, String.class);
        }

        @Override
        public final void setBytes(int parameterIndex, byte[] x) throws SQLException {
            bindNullable(parameterIndex, x, byte[].class);
        }

        @Override
        public final void setDate(int parameterIndex, Date x) throws SQLException {
            bindNullable(parameterIndex, x, LocalDate.class, Date::toLocalDate);
        }

        @Override
        public final void setTime(int parameterIndex, Time x) throws SQLException {
            bindNullable(parameterIndex, x, LocalTime.class, Time::toLocalTime);
        }

        @Override
        public final void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
            bindNullable(parameterIndex, x, LocalDateTime.class, Timestamp::toLocalDateTime);
        }

        @SuppressWarnings("unchecked")
        @Override
        public final void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
            bindNullable(parameterIndex, x, (Class<Object>) type(targetSqlType));
        }

        @Override
        public final void setObject(int parameterIndex, Object x) throws SQLException {
            bindNullable(parameterIndex, x, Object.class);
        }

        @Override
        public final void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
            setObject(parameterIndex, x, targetSqlType);
        }

        @Override
        public final void setObject(int parameterIndex, Object x, SQLType targetSqlType, int scaleOrLength) throws SQLException {
            setObject(parameterIndex, x, defaultIfNull(targetSqlType.getVendorTypeNumber(), Types.OTHER));
        }

        @Override
        public final void setObject(int parameterIndex, Object x, SQLType targetSqlType) throws SQLException {
            setObject(parameterIndex, x, defaultIfNull(targetSqlType.getVendorTypeNumber(), Types.OTHER));
        }


















    }

    static final class R2DBCResultSet extends DefaultResultSet {

        final Configuration c;
        final Row           r;
        boolean             wasNull;

        R2DBCResultSet(Configuration c, Row r) {
            super(null, null, () -> new SQLFeatureNotSupportedException("Unsupported operation of the JDBC to R2DBC bridge."));

            this.c = c;
            this.r = r;
        }

        private final <T> T wasNull(T nullable) {
            wasNull = nullable == null;
            return nullable;
        }

        private final <T> T nullable(int columnIndex, Class<T> type) {
            return nullable(columnIndex, type, t -> t);
        }

        private final <T, U> U nullable(int columnIndex, Class<T> type, Function<? super T, ? extends U> conversion) {
            T t = wasNull(r.get(columnIndex - 1, type));
            return wasNull ? null : conversion.apply(t);
        }

        private final <U> U nullable(int columnIndex, Function<? super Object, ? extends U> conversion) {
            Object t = wasNull(r.get(columnIndex - 1));
            return wasNull ? null : conversion.apply(t);
        }

        private final <T> T nonNull(int columnIndex, Class<T> type, T nullValue) {
            T t = wasNull(r.get(columnIndex - 1, type));
            return wasNull ? nullValue : t;
        }

        @Override
        public final boolean wasNull() throws SQLException {
            return wasNull;
        }

        @Override
        public final boolean getBoolean(int columnIndex) throws SQLException {
            return nonNull(columnIndex, Boolean.class, false);
        }

        @Override
        public final byte getByte(int columnIndex) throws SQLException {
            return nonNull(columnIndex, Byte.class, (byte) 0);
        }

        @Override
        public final short getShort(int columnIndex) throws SQLException {
            return nonNull(columnIndex, Short.class, (short) 0);
        }

        @Override
        public final int getInt(int columnIndex) throws SQLException {
            return nonNull(columnIndex, Integer.class, 0);
        }

        @Override
        public final long getLong(int columnIndex) throws SQLException {
            return nonNull(columnIndex, Long.class, 0L);
        }

        @Override
        public final float getFloat(int columnIndex) throws SQLException {
            return nonNull(columnIndex, Float.class, 0.0f);
        }

        @Override
        public final double getDouble(int columnIndex) throws SQLException {
            return nonNull(columnIndex, Double.class, 0.0);
        }

        @Override
        public final BigDecimal getBigDecimal(int columnIndex) throws SQLException {
            return nullable(columnIndex, BigDecimal.class);
        }

        @Override
        public final String getString(int columnIndex) throws SQLException {
            return nullable(columnIndex, String.class);
        }

        @Override
        public final byte[] getBytes(int columnIndex) throws SQLException {







            return nullable(columnIndex, byte[].class);
        }

        @Override
        public final Date getDate(int columnIndex) throws SQLException {
            return nullable(columnIndex, LocalDate.class, Date::valueOf);
        }

        @Override
        public final Time getTime(int columnIndex) throws SQLException {
            return nullable(columnIndex, LocalTime.class, Time::valueOf);
        }

        @Override
        public final Timestamp getTimestamp(int columnIndex) throws SQLException {
            return nullable(columnIndex, LocalDateTime.class, Timestamp::valueOf);
        }

        @Override
        public final Object getObject(int columnIndex) throws SQLException {
            return getObject(columnIndex, Object.class);
        }

        @Override
        public final <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
            return nullable(columnIndex, type);
        }

        @Override
        public final Array getArray(int columnIndex) throws SQLException {
            return new MockArray<>(c.dialect(), (Object[]) nullable(columnIndex, Object.class), Object[].class);
        }
    }

    static final class R2DBCResultSetMetaData implements ResultSetMetaData {

        final Configuration c;
        final RowMetadata   m;

        R2DBCResultSetMetaData(Configuration c, RowMetadata m) {
            this.c = c;
            this.m = m;
        }

        private final ColumnMetadata meta(int column) {
            return m.getColumnMetadata(column - 1);
        }

        @Override
        public final <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLFeatureNotSupportedException("R2DBC can't unwrap JDBC types");
        }

        @Override
        public final boolean isWrapperFor(Class<?> iface) throws SQLException {
            return false;
        }

        @Override
        public final int getColumnCount() throws SQLException {
            return m.getColumnNames().size();
        }

        @Override
        public final int isNullable(int column) throws SQLException {
            switch (meta(column).getNullability()) {
                case NON_NULL:
                    return ResultSetMetaData.columnNoNulls;
                case NULLABLE:
                    return ResultSetMetaData.columnNullable;
                case UNKNOWN:
                    return ResultSetMetaData.columnNullableUnknown;
                default:
                    throw new SQLFeatureNotSupportedException("Nullability: " + meta(column).getNullability().toString());
            }
        }

        @Override
        public final String getCatalogName(int column) throws SQLException {
            return "";
        }

        @Override
        public final String getSchemaName(int column) throws SQLException {
            return "";
        }

        @Override
        public final String getTableName(int column) throws SQLException {
            return "";
        }

        @Override
        public final String getColumnLabel(int column) throws SQLException {
            return getColumnName(column);
        }

        @Override
        public final String getColumnName(int column) throws SQLException {
            return meta(column).getName();
        }

        @Override
        public final int getPrecision(int column) throws SQLException {
            return defaultIfNull(meta(column).getPrecision(), 0);
        }

        @Override
        public final int getScale(int column) throws SQLException {
            return defaultIfNull(meta(column).getScale(), 0);
        }

        private final Class<?> getType(int column) {
            return defaultIfNull(meta(column).getJavaType(), Object.class);
        }

        private final DataType<?> getDataType(int column) {
            return DefaultDataType.getDataType(c.family(), getType(column));
        }

        @Override
        public final int getColumnType(int column) throws SQLException {
            return getDataType(column).getSQLType();
        }

        @Override
        public final String getColumnClassName(int column) throws SQLException {
            return getType(column).getName();
        }

        @Override
        public final String getColumnTypeName(int column) throws SQLException {
            if (is_0_9) {
                try {
                    return meta(column).getType().getName();
                }

                // ColumnMetadata::getType was added in 0.9
                catch (AbstractMethodError e) {
                    is_0_9 = false;
                }
            }

            return getDataType(column).getName();
        }

        @Override
        public final boolean isReadOnly(int column) throws SQLException {
            return false;
        }

        @Override
        public final boolean isWritable(int column) throws SQLException {
            return true;
        }

        @Override
        public final boolean isDefinitelyWritable(int column) throws SQLException {
            return true;
        }

        @Override
        public final boolean isSigned(int column) throws SQLException {
            return false;
        }

        @Override
        public final int getColumnDisplaySize(int column) throws SQLException {
            return 0;
        }

        @Override
        public final boolean isAutoIncrement(int column) throws SQLException {
            return false;
        }

        @Override
        public final boolean isCaseSensitive(int column) throws SQLException {
            return false;
        }

        @Override
        public final boolean isSearchable(int column) throws SQLException {
            return false;
        }

        @Override
        public final boolean isCurrency(int column) throws SQLException {
            return false;
        }
    }

    static final Settings setParamType(SQLDialect dialect, Settings settings) {
        settings = SettingsTools.clone(settings);

        switch (dialect.family()) {
            case MARIADB:
            case MYSQL:

                return settings.withParamType(NAMED);









            default:
                return settings
                    .withParamType(NAMED)
                    .withRenderNamedParamPrefix("$")
                    .withParseNamedParamPrefix("$");
        }
    }

    // -------------------------------------------------------------------------
    // XXX: Legacy implementation
    // -------------------------------------------------------------------------

    static final class BlockingRecordSubscription<R extends Record> extends AbstractSubscription<R> {
        private final ResultQueryTrait<R> query;
        private volatile Cursor<R>        c;

        BlockingRecordSubscription(ResultQueryTrait<R> query, Subscriber<? super R> subscriber) {
            super(subscriber);

            this.query = query;
        }

        @Override
        final synchronized void request0() {
            try {
                if (c == null)
                    c = query.fetchLazyNonAutoClosing();

                while (moreRequested()) {
                    R r = c.fetchNext();

                    if (r == null) {
                        subscriber.onComplete();
                        safeClose(c);
                        break;
                    }

                    subscriber.onNext(r);
                }
            }
            catch (Throwable t) {
                subscriber.onError(t);
                safeClose(c);
            }
        }

        @Override
        final void cancel0(boolean cancelled) {
            safeClose(c);
        }
    }

    static final class BlockingRowCountSubscription extends AbstractSubscription<Integer> {
        final AbstractRowCountQuery query;

        BlockingRowCountSubscription(AbstractRowCountQuery query, Subscriber<? super Integer> subscriber) {
            super(subscriber);

            this.query = query;
        }

        @Override
        final void request0() {
            try {
                subscriber.onNext(query.execute());
                subscriber.onComplete();
            }
            catch (Throwable t) {
                subscriber.onError(t);
            }
        }
    }
}