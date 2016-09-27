/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.client.solrj.io.stream;

import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.CloudSolrClient.Builder;
import org.apache.solr.client.solrj.io.SolrClientCache;
import org.apache.solr.client.solrj.io.Tuple;
import org.apache.solr.client.solrj.io.comp.StreamComparator;
import org.apache.solr.client.solrj.io.stream.expr.Explanation;
import org.apache.solr.client.solrj.io.stream.expr.Explanation.ExpressionType;
import org.apache.solr.client.solrj.io.stream.expr.Expressible;
import org.apache.solr.client.solrj.io.stream.expr.StreamExplanation;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpression;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpressionNamedParameter;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpressionParameter;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpressionValue;
import org.apache.solr.client.solrj.io.stream.expr.StreamFactory;
import org.apache.solr.client.solrj.io.stream.metrics.Metric;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.MapSolrParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;

public class StatsStream extends TupleStream implements Expressible  {

  private static final long serialVersionUID = 1;

  private Metric[] metrics;
  private String zkHost;
  private Tuple tuple;
  private SolrParams params;
  private String collection;
  private boolean done;
  private boolean doCount;
  private transient SolrClientCache cache;
  private transient CloudSolrClient cloudSolrClient;

  // Use StatsStream(String, String, SolrParams, Metric[]
  @Deprecated
  public StatsStream(String zkHost,
                     String collection,
                     Map<String, String> props,
                     Metric[] metrics) {
    init(zkHost, collection, new MapSolrParams(props), metrics);
  }

  public StatsStream(String zkHost,
                     String collection,
                     SolrParams params,
                     Metric[] metrics) {
    init(zkHost, collection, params, metrics);
  }

  private void init(String zkHost, String collection, SolrParams params, Metric[] metrics) {
    this.zkHost  = zkHost;
    this.params = params;
    this.metrics = metrics;
    this.collection = collection;
  }

  public StatsStream(StreamExpression expression, StreamFactory factory) throws IOException {
    // grab all parameters out
    String collectionName = factory.getValueOperand(expression, 0);
    List<StreamExpressionNamedParameter> namedParams = factory.getNamedOperands(expression);
    List<StreamExpression> metricExpressions = factory.getExpressionOperandsRepresentingTypes(expression, Metric.class);
    StreamExpressionNamedParameter zkHostExpression = factory.getNamedOperand(expression, "zkHost");
    
    // Validate there are no unknown parameters - zkHost is namedParameter so we don't need to count it twice
    if(expression.getParameters().size() != 1 + namedParams.size() + metricExpressions.size()){
      throw new IOException(String.format(Locale.ROOT,"invalid expression %s - unknown operands found",expression));
    }
    
    // Collection Name
    if(null == collectionName){
      throw new IOException(String.format(Locale.ROOT,"invalid expression %s - collectionName expected as first operand",expression));
    }
        
    // Named parameters - passed directly to solr as solrparams
    if(0 == namedParams.size()){
      throw new IOException(String.format(Locale.ROOT,"invalid expression %s - at least one named parameter expected. eg. 'q=*:*'",expression));
    }

    ModifiableSolrParams params = new ModifiableSolrParams();
    namedParams.stream().filter(namedParam -> !namedParam.getName().equals("zkHost"))
        .forEach(namedParam -> params.set(namedParam.getName(), namedParam.getParameter().toString().trim()));
    
    // zkHost, optional - if not provided then will look into factory list to get
    String zkHost = null;
    if(null == zkHostExpression){
      zkHost = factory.getCollectionZkHost(collectionName);
      if(zkHost == null) {
        zkHost = factory.getDefaultZkHost();
      }
    }
    else if(zkHostExpression.getParameter() instanceof StreamExpressionValue){
      zkHost = ((StreamExpressionValue)zkHostExpression.getParameter()).getValue();
    }
    if(null == zkHost){
      throw new IOException(String.format(Locale.ROOT,"invalid expression %s - zkHost not found for collection '%s'",expression,collectionName));
    }
    
    // metrics, optional - if not provided then why are you using this?
    Metric[] metrics = new Metric[metricExpressions.size()];
    for(int idx = 0; idx < metricExpressions.size(); ++idx){
      metrics[idx] = factory.constructMetric(metricExpressions.get(idx));
    }
    
    // We've got all the required items
    init(zkHost, collectionName, params, metrics);
  }
  
  @Override
  public StreamExpressionParameter toExpression(StreamFactory factory) throws IOException {
    // functionName(collectionName, param1, param2, ..., paramN, sort="comp", sum(fieldA), avg(fieldB))
    
    // function name
    StreamExpression expression = new StreamExpression(factory.getFunctionName(this.getClass()));
    
    // collection
    expression.addParameter(collection);
    
    // parameters
    ModifiableSolrParams mParams = new ModifiableSolrParams(params);
    for (Entry<String, String[]> param : mParams.getMap().entrySet()) {
      expression.addParameter(new StreamExpressionNamedParameter(param.getKey(), String.join(",", (CharSequence[]) param.getValue())));
    }
    
    // zkHost
    expression.addParameter(new StreamExpressionNamedParameter("zkHost", zkHost));
    
    // metrics
    for(Metric metric : metrics){
      expression.addParameter(metric.toExpression(factory));
    }
    
    return expression;   
  }
  
  @Override
  public Explanation toExplanation(StreamFactory factory) throws IOException {

    StreamExplanation explanation = new StreamExplanation(getStreamNodeId().toString());
    
    explanation.setFunctionName(factory.getFunctionName(this.getClass()));
    explanation.setImplementingClass(this.getClass().getName());
    explanation.setExpressionType(ExpressionType.STREAM_SOURCE);
    explanation.setExpression(toExpression(factory).toString());
    
    StreamExplanation child = new StreamExplanation(getStreamNodeId() + "-datastore");
    child.setFunctionName("solr (worker ? of ?)");
      // TODO: fix this so we know the # of workers - check with Joel about a Stat's ability to be in a
      // parallel stream.
    
    child.setImplementingClass("Solr/Lucene");
    child.setExpressionType(ExpressionType.DATASTORE);
    ModifiableSolrParams mParams = new ModifiableSolrParams(params);
    child.setExpression(mParams.getMap().entrySet().stream().map(e -> String.format(Locale.ROOT, "%s=%s", e.getKey(), Arrays.toString(e.getValue()))).collect(Collectors.joining(",")));
    explanation.addChild(child);
    
    return explanation;
  }

  public void setStreamContext(StreamContext context) {
    cache = context.getSolrClientCache();
  }

  public List<TupleStream> children() {
    return new ArrayList<>();
  }

  public void open() throws IOException {
    if(cache != null) {
      cloudSolrClient = cache.getCloudSolrClient(zkHost);
    } else {
      cloudSolrClient = new Builder()
          .withZkHost(zkHost)
          .build();
    }

    ModifiableSolrParams paramsLoc = new ModifiableSolrParams(this.params);
    addStats(paramsLoc, metrics);
    paramsLoc.set("stats", "true");
    paramsLoc.set("rows", "0");

    QueryRequest request = new QueryRequest(paramsLoc);
    try {
      NamedList response = cloudSolrClient.request(request, collection);
      this.tuple = getTuple(response);
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  public void close() throws IOException {
    if(cache == null) {
      cloudSolrClient.close();
    }
  }

  public Tuple read() throws IOException {
    if(!done) {
      done = true;
      return tuple;
    } else {
      Map<String, Object> fields = new HashMap<>();
      fields.put("EOF", true);
      return new Tuple(fields);
    }
  }

  public StreamComparator getStreamSort() {
    return null;
  }

  private void addStats(ModifiableSolrParams params, Metric[] _metrics) {
    Map<String, List<String>> m = new HashMap<>();
    for(Metric metric : _metrics) {
      String metricId = metric.getIdentifier();
      if(metricId.contains("(")) {
        metricId = metricId.substring(0, metricId.length()-1);
        String[] parts = metricId.split("\\(");
        String function = parts[0];
        String column = parts[1];
        List<String> stats = m.get(column);

        if(stats == null) {
          stats = new ArrayList<>();
        }

        if (!column.equals("*")) {
          m.put(column, stats);
        }

        switch (function) {
          case "min":
          case "max":
          case "sum":
            stats.add(function);
            break;
          case "avg":
          stats.add("mean");
            break;
          case "count":
          this.doCount = true;
            break;
        }
      }
    }

    for(String field : m.keySet()) {
      StringBuilder buf = new StringBuilder();
      List<String> stats = m.get(field);
      buf.append("{!");

      for(String stat : stats) {
        buf.append(stat).append("=").append("true ");
      }

      buf.append("}").append(field);
      params.add("stats.field", buf.toString());
    }
  }

  private Tuple getTuple(NamedList response) {

    Map<String, Object> map = new HashMap<>();

    if(doCount) {
      SolrDocumentList solrDocumentList = (SolrDocumentList) response.get("response");
      map.put("count(*)", solrDocumentList.getNumFound());
    }

    NamedList stats = (NamedList)response.get("stats");
    NamedList statsFields = (NamedList)stats.get("stats_fields");

    for(int i=0; i<statsFields.size(); i++) {
      String field = statsFields.getName(i);
      NamedList theStats = (NamedList)statsFields.getVal(i);
      for(int s=0; s<theStats.size(); s++) {
        addStat(map, field, theStats.getName(s), theStats.getVal(s));
      }
    }

    return new Tuple(map);
  }

  public int getCost() {
    return 0;
  }

  private void addStat(Map<String, Object> map, String field, String stat, Object val) {
    Object realVal = getRealVal(val);
    if(stat.equals("mean")) {
      map.put("avg("+field+")", realVal);
    } else {
      map.put(stat+"("+field+")", realVal);
    }
  }

  private Object getRealVal(Object val) {
    // Check if Double is really a Long
    if(val instanceof Double) {
      Double doubleVal = (double) val;
      //make sure that double has no decimals and fits within Long
      if(doubleVal % 1 == 0 && doubleVal >= Long.MIN_VALUE && doubleVal <= Long.MAX_VALUE) {
        return doubleVal.longValue();
      }
      return doubleVal;
    }

    // Wasn't a double so just return original Object
    return val;
  }
}
