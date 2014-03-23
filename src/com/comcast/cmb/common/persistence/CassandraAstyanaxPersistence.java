package com.comcast.cmb.common.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Logger;

import com.comcast.cmb.common.controller.CMBControllerServlet;
import com.comcast.cmb.common.util.CMBErrorCodes;
import com.comcast.cmb.common.util.CMBProperties;
import com.comcast.cmb.common.util.PersistenceException;
import com.comcast.cmb.common.util.ValueAccumulator.AccumulatorName;

import com.netflix.astyanax.AstyanaxContext;
import com.netflix.astyanax.ColumnListMutation;
import com.netflix.astyanax.Keyspace;
import com.netflix.astyanax.MutationBatch;
import com.netflix.astyanax.Serializer;
import com.netflix.astyanax.connectionpool.NodeDiscoveryType;
import com.netflix.astyanax.connectionpool.OperationResult;
import com.netflix.astyanax.connectionpool.exceptions.ConnectionException;
import com.netflix.astyanax.connectionpool.impl.ConnectionPoolConfigurationImpl;
import com.netflix.astyanax.connectionpool.impl.CountingConnectionPoolMonitor;
import com.netflix.astyanax.impl.AstyanaxConfigurationImpl;
import com.netflix.astyanax.model.Column;
import com.netflix.astyanax.model.ColumnFamily;
import com.netflix.astyanax.model.ColumnList;
import com.netflix.astyanax.model.Composite;
import com.netflix.astyanax.model.Row;
import com.netflix.astyanax.model.Rows;
import com.netflix.astyanax.query.IndexQuery;
import com.netflix.astyanax.query.RowQuery;
import com.netflix.astyanax.serializers.CompositeSerializer;
import com.netflix.astyanax.serializers.LongSerializer;
import com.netflix.astyanax.serializers.StringSerializer;
import com.netflix.astyanax.thrift.ThriftFamilyFactory;
import com.netflix.astyanax.util.RangeBuilder;

public class CassandraAstyanaxPersistence extends AbstractCassandraPersistence {
	
	// TODO: set consistency level everywhere
	// TODO: timeout exception
	
	private static Map<String, Keyspace> keyspaces = new HashMap<String, Keyspace>();
	
	private static Logger logger = Logger.getLogger(CassandraAstyanaxPersistence.class);
	
	public CassandraAstyanaxPersistence() {
		initPersistence();
	}
	
	private void initPersistence() {
		
		List<String> keyspaceNames = new ArrayList<String>();
		keyspaceNames.add(CMBProperties.getInstance().getCMBKeyspace());
		keyspaceNames.add(CMBProperties.getInstance().getCNSKeyspace());
		keyspaceNames.add(CMBProperties.getInstance().getCQSKeyspace());
		
		for (String k : keyspaceNames) {
		
			AstyanaxContext<Keyspace> context = new AstyanaxContext.Builder()
			.forCluster(CLUSTER_NAME)
			.forKeyspace(CMBProperties.getInstance().getCMBKeyspace())
			.withAstyanaxConfiguration(new AstyanaxConfigurationImpl()      
			.setDiscoveryType(NodeDiscoveryType.RING_DESCRIBE)
					)
					.withConnectionPoolConfiguration(new ConnectionPoolConfigurationImpl("CMBAstyananxConnectionPool")
					//.setPort(9160)
					.setMaxConnsPerHost(1)
					.setSeeds(AbstractCassandraPersistence.CLUSTER_URL)
							)
							.withConnectionPoolMonitor(new CountingConnectionPoolMonitor())
							.buildKeyspace(ThriftFamilyFactory.getInstance());
	
			context.start();
			Keyspace keyspace = context.getClient();
			
			keyspaces.put(CMBProperties.getInstance().getCMBKeyspace(), keyspace);
		}
	}
	
	private Keyspace getKeyspace(String keyspace) {
		return keyspaces.get(keyspace);
	}

	private static Serializer getSerializer(CmbSerializer s) throws PersistenceException {
		if (s instanceof CmbStringSerializer) {
			return StringSerializer.get();
		} else if (s instanceof CmbCompositeSerializer) {
			return CompositeSerializer.get();
		} else if (s instanceof CmbLongSerializer) {
			return LongSerializer.get();
		}
		throw new PersistenceException(CMBErrorCodes.InternalError, "Unknown serializer " + s);
	}

	private static Object getComposite(Object o) {
		if (o == null) {
			return null;
		}
		if (o instanceof CmbAstyanaxComposite) {
			return ((CmbAstyanaxComposite)o).getComposite();
		}
		return o;
	}

	private static Collection<Object> getComposites(Collection l) {
		Collection<Object> r = new ArrayList<Object>();
		if (l == null) {
			return null;
		}
		for (Object o : l) {
			r.add(getComposite(o));
		}
		return r;
	}

	private static Object getCmbComposite(Object o) {
		if (o == null) {
			return null;
		}
		if (o instanceof Composite) {
			return new CmbAstyanaxComposite((Composite)o);
		}
		return o;
	}

	@Override
	public CmbComposite getCmbComposite(List<?> l) {
		return new CmbAstyanaxComposite(l);
	}

	@Override
	public CmbComposite getCmbComposite(Object... os) {
		return new CmbAstyanaxComposite(os);
	}

	public static class CmbAstyanaxComposite extends CmbComposite {
		private final Composite composite;
		public CmbAstyanaxComposite(List<?> l) {
			composite = new Composite(l);
		}
		public CmbAstyanaxComposite(Object... os) {
			composite = new Composite(os);
		}
		public CmbAstyanaxComposite(Composite composite) {
			this.composite = composite;
		}
		public Composite getComposite() {
			return composite;
		}
		@Override
		public Object get(int i) {
			return composite.get(i);
		}
		@Override
		public String toString() {
			return composite.toString();
		}
		@Override
		public int compareTo(CmbComposite c) {
			return this.composite.compareTo(((CmbAstyanaxComposite)c).getComposite());
		}
		@Override
		public boolean equals(Object o) {
			if (!(o instanceof CmbAstyanaxComposite)) {
				return false;
			}
			return this.composite.equals(((CmbAstyanaxComposite)o).getComposite());
		}
	}

	public static class CmbAstyanaxColumn<N, V> extends CmbColumn<N, V> {
		private Column<N> astyanaxColumn;
		public CmbAstyanaxColumn(Column<N> hectorColumn) {
			this.astyanaxColumn = hectorColumn;
		}
		@Override
		public N getName() {
			return (N)getCmbComposite(astyanaxColumn.getName());
		}
		@Override
		public V getValue() {
			//TODO: support types other than String as well
			return (V)astyanaxColumn.getValue(StringSerializer.get());
		}
		@Override
		public long getClock() {
			return astyanaxColumn.getTimestamp();
		}
	}

	public static class CmbAstyanaxRow<K, N, V> extends CmbRow<K, N, V> {
		private K key;
		private ColumnList<N> columns;
		public CmbAstyanaxRow(Row<K, N> row) {
			this.key = row.getKey();
			this.columns = row.getColumns();
		}
		public CmbAstyanaxRow(K key, ColumnList<N> columns) {
			this.key = key;
			this.columns = columns;
		}
		@Override
		public K getKey() {
			return this.key;
		}
		@Override
		public CmbColumnSlice<N, V> getColumnSlice() {
			return new CmbAstyanaxColumnSlice<N, V>(columns);
		}
	}

	public static class CmbAstyanaxColumnSlice<N, V> extends CmbColumnSlice<N, V> {
		private ColumnList<N> astyanaxColumns;
		private List<CmbColumn<N, V>> columns = null;
		public CmbAstyanaxColumnSlice(ColumnList<N> columns) {
			this.astyanaxColumns = columns;
		}
		@Override
		public CmbAstyanaxColumn<N, V> getColumnByName(N name) {
			if (astyanaxColumns.getColumnByName(name) != null) {
				return new CmbAstyanaxColumn<N, V>(astyanaxColumns.getColumnByName(name));
			} else {
				return null;
			}
		}
		private void loadColumns() {
			if (columns == null) {
				columns = new ArrayList<CmbColumn<N, V>>();
				for (Column<N> c : astyanaxColumns) {
					columns.add(new CmbAstyanaxColumn<N, V>(c));
				}
			}
		}
		@Override
		public List<CmbColumn<N, V>> getColumns() {
			loadColumns();
			return columns;
		}
		@Override
		public int size() {
			loadColumns();
			return columns.size();
		}
	}

	/*public static class CmbAstyanaxSuperColumnSlice<SN, N, V> extends CmbSuperColumnSlice<SN, N, V> {
		private SuperSlice<SN, N, V> hectorSuperSlice;
		public CmbAstyanaxSuperColumnSlice(SuperSlice<SN, N, V> hectorSuperSlice) {
			this.hectorSuperSlice = hectorSuperSlice;
		}
		@Override
		public CmbAstyanaxSuperColumn<SN, N, V> getColumnByName(SN name) {
			if (hectorSuperSlice.getColumnByName(name) != null) {
				return new CmbAstyanaxSuperColumn<SN, N, V>(hectorSuperSlice.getColumnByName(name));
			} else {
				return null;
			}
		}
		@Override
		public List<CmbSuperColumn<SN, N, V>> getSuperColumns() {
			List<CmbSuperColumn<SN, N, V>> superColumns = new ArrayList<CmbSuperColumn<SN, N, V>>();
			for (HSuperColumn<SN, N, V> sc : hectorSuperSlice.getSuperColumns()) {
				superColumns.add(new CmbAstyanaxSuperColumn<SN, N, V>(sc));
			}
			return superColumns;
		}
	}*/

	/*public static class CmbAstyanaxSuperColumn<SN, N, V> extends CmbSuperColumn<SN, N, V> {
		private SuperColumn<SN, N, V> hectorSuperColumn;
		public CmbAstyanaxSuperColumn(HSuperColumn<SN, N, V> hectorSuperColumn) {
			this.hectorSuperColumn = hectorSuperColumn;
		}
		@Override
		public SN getName() {
			return (SN)getCmbComposite(hectorSuperColumn.getName());
		}
		@Override
		public List<CmbColumn<N, V>> getColumns() {
			List<CmbColumn<N, V>> columns = new ArrayList<CmbColumn<N, V>>();
			for (HColumn<N, V> c : this.hectorSuperColumn.getColumns()) {
				columns.add(new CmbAstyanaxColumn<N, V>(c));
			}
			return columns;
		}
	}*/

	private <K, N, V> List<CmbRow<K, N, V>> getRows(List<Row<K, N>> rows) throws PersistenceException {
		List<CmbRow<K, N, V>> l = new ArrayList<CmbRow<K, N, V>>();
		for (Row<K, N> r : rows) {
			l.add(new CmbAstyanaxRow<K, N, V>(r));
		}
		return l;
	}
	
	private <K, N, V> List<CmbRow<K, N, V>> getRows(Rows<K, N> rows) throws PersistenceException {
		List<CmbRow<K, N, V>> l = new ArrayList<CmbRow<K, N, V>>();
		for (int i=0; i<rows.size(); i++) {
			Row<K, N> r = rows.getRowByIndex(i);
			l.add(new CmbAstyanaxRow<K, N, V>(r));
		}
		return l;
	}

	/*private <SN, N, V> List<CmbSuperColumn<SN, N, V>> getSuperColumns(List<SuperColumn<SN, N, V>> superColumns) throws PersistenceException {
		List<CmbSuperColumn<SN, N, V>> l = new ArrayList<CmbSuperColumn<SN, N, V>>();
		for (SuperColumn<SN, N, V> superColumn : superColumns) {
			l.add(new CmbAstyanaxSuperColumn<SN, N, V>(superColumn));
		}
		return l;
	}*/
	
	@Override
	public boolean isAlive() {
		// TODO: implement
		return true;
	}
	
	private static ConcurrentHashMap<String, ColumnFamily> cf = new ConcurrentHashMap<String, ColumnFamily>();
	
	static {
		
		ColumnFamily<String, String> CF_CNS_TOPICS =
				new ColumnFamily<String, String>(
						CNS_TOPICS,  // column family name
						StringSerializer.get(), // key serializer
						StringSerializer.get()); // column serializer
		cf.put(CNS_TOPICS, CF_CNS_TOPICS);

		ColumnFamily<String, String> CF_CNS_TOPICS_BY_USER_ID =
				new ColumnFamily<String, String>(
						CNS_TOPICS_BY_USER_ID,  // column family name
						StringSerializer.get(), // key serializer
						StringSerializer.get()); // column serializer
		cf.put(CNS_TOPICS_BY_USER_ID, CF_CNS_TOPICS);

		ColumnFamily<String, String> CF_CNS_TOPIC_SUBSCRIPTIONS =
				new ColumnFamily<String, String>(
						CNS_TOPIC_SUBSCRIPTIONS,  // column family name
						StringSerializer.get(), // key serializer
						StringSerializer.get()); // column serializer
		cf.put(CNS_TOPIC_SUBSCRIPTIONS, CF_CNS_TOPICS);

		ColumnFamily<String, String> CF_CNS_TOPIC_SUBSCRIPTIONS_INDEX =
				new ColumnFamily<String, String>(
						CNS_TOPIC_SUBSCRIPTIONS_INDEX,  // column family name
						StringSerializer.get(), // key serializer
						StringSerializer.get()); // column serializer
		cf.put(CNS_TOPIC_SUBSCRIPTIONS_INDEX, CF_CNS_TOPICS);

		ColumnFamily<String, String> CF_CNS_TOPIC_SUBSCRIPTIONS_USER_INDEX =
				new ColumnFamily<String, String>(
						CNS_TOPIC_SUBSCRIPTIONS_USER_INDEX,  // column family name
						StringSerializer.get(), // key serializer
						StringSerializer.get()); // column serializer
		cf.put(CNS_TOPIC_SUBSCRIPTIONS_USER_INDEX, CF_CNS_TOPICS);
		
		ColumnFamily<String, String> CF_CNS_TOPIC_SUBSCRIPTIONS_TOKEN_INDEX =
				new ColumnFamily<String, String>(
						CNS_TOPIC_SUBSCRIPTIONS_TOKEN_INDEX,  // column family name
						StringSerializer.get(), // key serializer
						StringSerializer.get()); // column serializer
		cf.put(CNS_TOPIC_SUBSCRIPTIONS_TOKEN_INDEX, CF_CNS_TOPICS);

		ColumnFamily<String, String> CF_CNS_TOPIC_ATTRIBUTES =
				new ColumnFamily<String, String>(
						CNS_TOPIC_ATTRIBUTES,  // column family name
						StringSerializer.get(), // key serializer
						StringSerializer.get()); // column serializer
		cf.put(CNS_TOPIC_ATTRIBUTES, CF_CNS_TOPICS);

		ColumnFamily<String, String> CF_CNS_SUBSCRIPTION_ATTRIBUTES =
				new ColumnFamily<String, String>(
						CNS_SUBSCRIPTION_ATTRIBUTES,  // column family name
						StringSerializer.get(), // key serializer
						StringSerializer.get()); // column serializer
		cf.put(CNS_SUBSCRIPTION_ATTRIBUTES, CF_CNS_TOPICS);

		ColumnFamily<String, String> CF_CNS_TOPIC_STATS =
				new ColumnFamily<String, String>(
						CNS_TOPIC_STATS,  // column family name
						StringSerializer.get(), // key serializer
						StringSerializer.get()); // column serializer
		cf.put(CNS_TOPIC_STATS, CF_CNS_TOPICS);

		ColumnFamily<String, String> CF_CNS_WORKERS =
				new ColumnFamily<String, String>(
						CNS_WORKERS,  // column family name
						StringSerializer.get(), // key serializer
						StringSerializer.get()); // column serializer
		cf.put(CNS_WORKERS, CF_CNS_TOPICS);

		ColumnFamily<String, String> CF_CNS_API_SERVERS =
				new ColumnFamily<String, String>(
						CNS_API_SERVERS,  // column family name
						StringSerializer.get(), // key serializer
						StringSerializer.get()); // column serializer
		cf.put(CNS_API_SERVERS, CF_CNS_TOPICS);
	
		ColumnFamily<String, String> CF_CQS_QUEUES =
				new ColumnFamily<String, String>(
						CQS_QUEUES,  // column family name
						StringSerializer.get(), // key serializer
						StringSerializer.get()); // column serializer
		cf.put(CQS_QUEUES, CF_CNS_TOPICS);
	
		ColumnFamily<String, String> CF_CQS_QUEUES_BY_USER_ID =
				new ColumnFamily<String, String>(
						CQS_QUEUES_BY_USER_ID,  // column family name
						StringSerializer.get(), // key serializer
						StringSerializer.get()); // column serializer
		cf.put(CQS_QUEUES_BY_USER_ID, CF_CNS_TOPICS);

		ColumnFamily<String, Composite> CF_CQS_PARTITIONED_QUEUE_MESSAGES =
				new ColumnFamily<String, Composite>(
						CQS_PARTITIONED_QUEUE_MESSAGES,  // column family name
						StringSerializer.get(), // key serializer
						CompositeSerializer.get()); // column serializer
		cf.put(CQS_PARTITIONED_QUEUE_MESSAGES, CF_CNS_TOPICS);

		ColumnFamily<String, String> CF_CQS_API_SERVERS =
				new ColumnFamily<String, String>(
						CQS_API_SERVERS,  // column family name
						StringSerializer.get(), // key serializer
						StringSerializer.get()); // column serializer
		cf.put(CQS_API_SERVERS, CF_CNS_TOPICS);

		ColumnFamily<String, String> CF_CMB_USERS =
				new ColumnFamily<String, String>(
						CMB_USERS,  // column family name
						StringSerializer.get(), // key serializer
						StringSerializer.get()); // column serializer
		cf.put(CMB_USERS, CF_CNS_TOPICS);
	}

	private static ColumnFamily getColumnFamily(String columnFamily) {
		return cf.get(columnFamily);
	}

	@Override
	public <K, N, V> void update(String keyspace, String columnFamily, K key,
			N column, V value, CmbSerializer keySerializer,
			CmbSerializer nameSerializer, CmbSerializer valueSerializer, Integer ttl)
			throws PersistenceException {
		
		long ts1 = System.currentTimeMillis();	    
		logger.debug("event=update column_family=" + columnFamily + " key=" + key + " column=" + column + " value=" + value);

		try {
			MutationBatch m = getKeyspace(keyspace).prepareMutationBatch();
			m.withRow((ColumnFamily<K, N>)getColumnFamily(columnFamily), key)
			.putColumn((N)getComposite(column), value, getSerializer(valueSerializer), ttl);
			OperationResult<Void> result = m.execute();
		} catch (ConnectionException ex) {
			throw new PersistenceException(ex);
		} finally {
			long ts2 = System.currentTimeMillis();
			CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraTime, (ts2 - ts1));
			CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraWrite, 1L);
		}
	}

	/*@Override
	public <K, SN, N, V> void insertSuperColumn(String keyspace,
			String columnFamily, K key, CmbSerializer keySerializer,
			SN superName, Integer ttl, CmbSerializer superNameSerializer,
			Map<N, V> subColumnNameValues, CmbSerializer columnSerializer,
			CmbSerializer valueSerializer) throws PersistenceException {
		// TODO : remove
	}*/

	/*@Override
	public <K, SN, N, V> void insertSuperColumns(String keyspace,
			String columnFamily, K key, CmbSerializer keySerializer,
			Map<SN, Map<N, V>> superNameSubColumnsMap, int ttl,
			CmbSerializer superNameSerializer,
			CmbSerializer columnSerializer, CmbSerializer valueSerializer)
			throws PersistenceException {
		// TODO : remove
	}*/
	
	// TODO: check if astyanax returns empty rows 

	@Override
	public <K, N, V> List<CmbRow<K, N, V>> readNextNNonEmptyRows(
			String keyspace, String columnFamily, K lastKey, int numRows,
			int numCols, CmbSerializer keySerializer,
			CmbSerializer columnNameSerializer, CmbSerializer valueSerializer)
			throws PersistenceException {

		long ts1 = System.currentTimeMillis();
		logger.debug("event=read_next_n_non_empty_rows cf=" + columnFamily + " last_key=" + lastKey + " num_rows=" + numRows + " num_cols=" + numCols);

		try {

		    Rows<K, N> rows = null;

		    OperationResult<Rows<K, N>> or = getKeyspace(keyspace).
		    		prepareQuery(getColumnFamily(columnFamily)).
		    		getRowRange(lastKey, null, null, null, numRows).
		    		withColumnRange(new RangeBuilder().setLimit(numCols).build()).
		    		execute();
		    rows = or.getResult();
			
			
			return getRows(rows);

		} catch (ConnectionException ex) {
		
			throw new PersistenceException(ex);

		} finally {

			long ts2 = System.currentTimeMillis();
			CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraTime, (ts2 - ts1));      
			CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraRead, 1L);
		}
	}

	@Override
	public <K, N, V> List<CmbRow<K, N, V>> readNextNRows(String keyspace,
			String columnFamily, K lastKey, int numRows, int numCols,
			CmbSerializer keySerializer, CmbSerializer columnNameSerializer,
			CmbSerializer valueSerializer) throws PersistenceException {

		long ts1 = System.currentTimeMillis();
		logger.debug("event=read_next_n_rows cf=" + columnFamily + " last_key=" + lastKey + " num_rows=" + numRows + " num_cols=" + numCols);

		try {

		    Rows<K, N> rows = null;

		    OperationResult<Rows<K, N>> or = getKeyspace(keyspace).
		    		prepareQuery(getColumnFamily(columnFamily)).
		    		getRowRange(lastKey, null, null, null, numRows).
		    		withColumnRange(new RangeBuilder().setLimit(numCols).build()).
		    		execute();
		    rows = or.getResult();
			
			return getRows(rows);

		} catch (ConnectionException ex) {
		
			throw new PersistenceException(ex);

		} finally {

			long ts2 = System.currentTimeMillis();
			CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraTime, (ts2 - ts1));      
			CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraRead, 1L);
		}
	}

	@Override
	public <K, N, V> List<CmbRow<K, N, V>> readNextNRows(String keyspace,
			String columnFamily, K lastKey, N whereColumn, V whereValue,
			int numRows, int numCols, CmbSerializer keySerializer,
			CmbSerializer columnNameSerializer, CmbSerializer valueSerializer)
			throws PersistenceException {

		long ts1 = System.currentTimeMillis();
		logger.debug("event=read_nextn_rows cf=" + columnFamily + " last_key=" + lastKey + " num_rows=" + numRows + " num_cols=" + numCols);

		try {

			IndexQuery<K, N> query = getKeyspace(keyspace).
					   prepareQuery(getColumnFamily(columnFamily)).
					   searchWithIndex().
					   addExpression().
					   whereColumn(whereColumn).equals().value(whereValue, getSerializer(valueSerializer));
			
			OperationResult<Rows<K, N>> or = query.execute();
			Rows<K, N> rows = or.getResult();
			return getRows(rows);

		} catch (ConnectionException ex) {
		
			throw new PersistenceException(ex);

		} finally {

			long ts2 = System.currentTimeMillis();
			CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraTime, (ts2 - ts1));      
			CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraRead, 1L);
		}
	}

	@Override
	public <K, N, V> List<CmbRow<K, N, V>> readNextNRows(String keyspace,
			String columnFamily, K lastKey, Map<N, V> columnValues,
			int numRows, int numCols, CmbSerializer keySerializer,
			CmbSerializer columnNameSerializer, CmbSerializer valueSerializer)
			throws PersistenceException {
		
		long ts1 = System.currentTimeMillis();
		logger.debug("event=read_nextn_rows cf=" + columnFamily + " last_key=" + lastKey + " num_rows=" + numRows + " num_cols=" + numCols);

		try {

			IndexQuery<K, N> query = getKeyspace(keyspace).
					   prepareQuery(getColumnFamily(columnFamily)).
					   searchWithIndex();
			
			for (N columnName : columnValues.keySet()) {
				query.addExpression().whereColumn(columnName).equals().value(columnValues.get(columnName), getSerializer(valueSerializer));
			}
			
			OperationResult<Rows<K, N>> or = query.execute();
			Rows<K, N> rows = or.getResult();
			return getRows(rows);

		} catch (ConnectionException ex) {
		
			throw new PersistenceException(ex);

		} finally {

			long ts2 = System.currentTimeMillis();
			CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraTime, (ts2 - ts1));      
			CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraRead, 1L);
		}
	}

	@Override
	public <K, N, V> CmbColumnSlice<N, V> readColumnSlice(String keyspace,
			String columnFamily, K key, N firstColumnName, N lastColumnName,
			int numCols, CmbSerializer keySerializer,
			CmbSerializer columnNameSerializer, CmbSerializer valueSerializer)
			throws PersistenceException {

		long ts1 = System.currentTimeMillis();
		logger.debug("event=read_column_slice cf=" + columnFamily + " key=" + key);

		try {

		    RowQuery<K, N> rq = getKeyspace(keyspace).
		    		prepareQuery(getColumnFamily(columnFamily)).getKey(key).
		    		withColumnRange(getComposite(firstColumnName), getComposite(lastColumnName), false, numCols);
		    ColumnList<N> columns = rq.execute().getResult();
			
			return new CmbAstyanaxColumnSlice(columns);

		} catch (ConnectionException ex) {
		
			throw new PersistenceException(ex);

		} finally {

			long ts2 = System.currentTimeMillis();
			CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraTime, (ts2 - ts1));      
			CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraRead, 1L);
		}
	}

	/*@Override
	public <K, SN, N, V> CmbSuperColumnSlice<SN, N, V> readRowFromSuperColumnFamily(
			String keyspace, String columnFamily, K key, SN firstColumnName,
			SN lastColumnName, int numCols, CmbSerializer keySerializer,
			CmbSerializer superNameSerializer,
			CmbSerializer columnNameSerializer, CmbSerializer valueSerializer)
			throws PersistenceException {
		// TODO : remove
		return null;
	}*/

	/*@Override
	public <K, SN, N, V> CmbSuperColumn<SN, N, V> readColumnFromSuperColumnFamily(
			String keyspace, String columnFamily, K key, SN columnName,
			CmbSerializer keySerializer, CmbSerializer superNameSerializer,
			CmbSerializer columnNameSerializer, CmbSerializer valueSerializer)
			throws PersistenceException {
		// TODO : remove
		return null;
	}*/

	/*@Override
	public <K, SN, N, V> List<CmbSuperColumn<SN, N, V>> readMultipleColumnsFromSuperColumnFamily(
			String keyspace, String columnFamily, Collection<K> keys,
			Collection<SN> columnNames, CmbSerializer keySerializer,
			CmbSerializer superNameSerializer,
			CmbSerializer columnNameSerializer, CmbSerializer valueSerializer)
			throws PersistenceException {
		// TODO : remove
		return null;
	}*/

	/*@Override
	public <K, SN, N, V> List<CmbSuperColumn<SN, N, V>> readColumnsFromSuperColumnFamily(
			String keyspace, String columnFamily, K key,
			CmbSerializer keySerializer, CmbSerializer superNameSerializer,
			CmbSerializer columnNameSerializer,
			CmbSerializer valueSerializer, SN firstCol, SN lastCol, int numCol)
			throws PersistenceException {
		// TODO : remove
		return null;
	}*/

	@Override
	public <K, N, V> void insertRow(String keyspace, K rowKey, 
			String columnFamily, Map<N, V> columnValues,
			CmbSerializer keySerializer, CmbSerializer nameSerializer,
			CmbSerializer valueSerializer, Integer ttl) throws PersistenceException {
		
		long ts1 = System.currentTimeMillis();	    
		logger.debug("event=insert_row column_family=" + columnFamily + " key=" + rowKey);

		try {
			MutationBatch m = getKeyspace(keyspace).prepareMutationBatch();
			ColumnListMutation<N> clm = m.withRow((ColumnFamily<K, N>)getColumnFamily(columnFamily), rowKey);
			for (N columnName : columnValues.keySet()) {
				clm.putColumn((N)getComposite(columnName), columnValues.get(columnName), getSerializer(valueSerializer), ttl);
				CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraWrite, 1L);
			}
			OperationResult<Void> result = m.execute();
		} catch (ConnectionException ex) {
			throw new PersistenceException(ex);
		} finally {
			long ts2 = System.currentTimeMillis();
			CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraTime, (ts2 - ts1));
		}
	}

	@Override
	public <K, N, V> void insertRows(String keyspace,
			Map<K, Map<N, V>> rowColumnValues, String columnFamily,
			CmbSerializer keySerializer, CmbSerializer nameSerializer,
			CmbSerializer valueSerializer, Integer ttl) throws PersistenceException {

		long ts1 = System.currentTimeMillis();	    
		logger.debug("event=insert_rows column_family=" + columnFamily);

		try {
			MutationBatch m = getKeyspace(keyspace).prepareMutationBatch();
			for (K rowKey : rowColumnValues.keySet()) { 
				ColumnListMutation<N> clm = m.withRow((ColumnFamily<K, N>)getColumnFamily(columnFamily), rowKey);
				for (N columnName : rowColumnValues.get(rowKey).keySet()) {
					clm.putColumn((N)getComposite(columnName), rowColumnValues.get(rowKey).get(columnName), getSerializer(valueSerializer), ttl);
					CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraWrite, 1L);
				}
			}
			OperationResult<Void> result = m.execute();
		} catch (ConnectionException ex) {
			throw new PersistenceException(ex);
		} finally {
			long ts2 = System.currentTimeMillis();
			CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraTime, (ts2 - ts1));
		}
	}

	@Override
	public <K, N> void delete(String keyspace, String columnFamily, K key,
			N column, CmbSerializer keySerializer,
			CmbSerializer columnSerializer) throws PersistenceException {

		long ts1 = System.currentTimeMillis();	    
		logger.debug("event=delete column_family=" + columnFamily + " key=" + key + " column=" + column);

		try {
			MutationBatch m = getKeyspace(keyspace).prepareMutationBatch();
			ColumnListMutation<N> clm = m.withRow((ColumnFamily<K, N>)getColumnFamily(columnFamily), key);
			if (column != null) {
				clm.deleteColumn((N)getComposite(column));
			} else {
				clm.delete();
			}
			OperationResult<Void> result = m.execute();
		} catch (ConnectionException ex) {
			throw new PersistenceException(ex);
		} finally {
			long ts2 = System.currentTimeMillis();
			CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraTime, (ts2 - ts1));
			CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraWrite, 1L);
		}
	}

	@Override
	public <K, N> void deleteBatch(String keyspace, String columnFamily,
			List<K> keyList, List<N> columnList, CmbSerializer keySerializer,
			CmbSerializer columnSerializer) throws PersistenceException {

		long ts1 = System.currentTimeMillis();	    
		logger.debug("event=delete_batch column_family=" + columnFamily);

		try {
			MutationBatch m = getKeyspace(keyspace).prepareMutationBatch();
			if (columnList == null || columnList.isEmpty()) {
				for (K k : keyList) {
					ColumnListMutation<N> clm = m.withRow((ColumnFamily<K, N>)getColumnFamily(columnFamily), k);
					clm.delete();
					CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraWrite, 1L);
				}
			} else {
				// TODO: review this logic with jane 
				for (int i=0; i< keyList.size();i++) {
					ColumnListMutation<N> clm = m.withRow((ColumnFamily<K, N>)getColumnFamily(columnFamily), keyList.get(i));
					clm.deleteColumn((N)getComposite(columnList.get(i)));
					CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraWrite, 1L);
				}
			}
			OperationResult<Void> result = m.execute();
		} catch (ConnectionException ex) {
			throw new PersistenceException(ex);
		} finally {
			long ts2 = System.currentTimeMillis();
			CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraTime, (ts2 - ts1));
		}
	}

	/*@Override
	public <K, SN, N> void deleteSuperColumn(String keyspace,
			String superColumnFamily, K key, SN superColumn,
			CmbSerializer keySerializer, CmbSerializer superColumnSerializer)
			throws PersistenceException {
		// TODO : remove
	}*/

	@Override
	public <K, N> int getCount(String keyspace, String columnFamily, K key,
			CmbSerializer keySerializer, CmbSerializer columnNameSerializer)
			throws PersistenceException {
		// TODO: implement
		return 0;
	}

	@Override
	public <K, N> void incrementCounter(String keyspace, String columnFamily,
			K rowKey, String columnName, int incrementBy,
			CmbSerializer keySerializer, CmbSerializer columnNameSerializer)
			throws PersistenceException {
		
		long ts1 = System.currentTimeMillis();	    
		logger.debug("event=increment_counter column_family=" + columnFamily);

		try {
			getKeyspace(keyspace).prepareColumnMutation(getColumnFamily(columnFamily), rowKey, columnName)
		    .incrementCounterColumn(incrementBy)
		    .execute();
		} catch (ConnectionException ex) {
			throw new PersistenceException(ex);
		} finally {
			long ts2 = System.currentTimeMillis();
			CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraTime, (ts2 - ts1));
			CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraWrite, 1L);
		}
	}

	@Override
	public <K, N> void decrementCounter(String keyspace, String columnFamily,
			K rowKey, String columnName, int decrementBy,
			CmbSerializer keySerializer, CmbSerializer columnNameSerializer)
			throws PersistenceException {

		long ts1 = System.currentTimeMillis();	    
		logger.debug("event=decrement_counter column_family=" + columnFamily);

		try {
			getKeyspace(keyspace).prepareColumnMutation(getColumnFamily(columnFamily), rowKey, columnName)
		    .incrementCounterColumn(-decrementBy)
		    .execute();
		} catch (ConnectionException ex) {
			throw new PersistenceException(ex);
		} finally {
			long ts2 = System.currentTimeMillis();
			CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraTime, (ts2 - ts1));
			CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraWrite, 1L);
		}
	}

	@Override
	public <K, N> void deleteCounter(String keyspace, String columnFamily,
			K rowKey, N columnName, CmbSerializer keySerializer,
			CmbSerializer columnNameSerializer) throws PersistenceException {
		
		long ts1 = System.currentTimeMillis();	    
		logger.debug("event=delete_counter column_family=" + columnFamily);

		try {
			getKeyspace(keyspace).prepareColumnMutation(getColumnFamily(columnFamily),rowKey,columnName).deleteCounterColumn().execute();
		} catch (ConnectionException ex) {
			throw new PersistenceException(ex);
		} finally {
			long ts2 = System.currentTimeMillis();
			CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraTime, (ts2 - ts1));
			CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraWrite, 1L);
		}
	}

	@Override
	public <K, N> long getCounter(String keyspace, String columnFamily,
			K rowKey, N columnName, CmbSerializer keySerializer,
			CmbSerializer columnNameSerializer) throws PersistenceException {
		long ts1 = System.currentTimeMillis();	    
		logger.debug("event=get_counter column_family=" + columnFamily);
		try {
			Column<N> result = getKeyspace(keyspace).prepareQuery((ColumnFamily<K, N>)getColumnFamily(columnFamily))
					.getKey(rowKey)
					.getColumn(columnName)
					.execute().getResult();
			Long counterValue = result.getLongValue();
			return counterValue;
		} catch (ConnectionException ex) {
			throw new PersistenceException(ex);
		} finally {
			long ts2 = System.currentTimeMillis();
			CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraTime, (ts2 - ts1));
			CMBControllerServlet.valueAccumulator.addToCounter(AccumulatorName.CassandraRead, 1L);
		}
	}

	@Override
	public <K, N, V> CmbColumn<N, V> readColumn(String keyspace,
			String columnFamily, K key, N columnName,
			CmbSerializer keySerializer, CmbSerializer columnNameSerializer,
			CmbSerializer valueSerializer) throws PersistenceException {
		// TODO Auto-generated method stub
		return null;
	}
}
