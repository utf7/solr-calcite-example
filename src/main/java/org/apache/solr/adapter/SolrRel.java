/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.adapter;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Relational expression that uses Solr calling convention.
 */
public interface SolrRel extends RelNode {
  void implement(Implementor implementor);

  /** Calling convention for relational operations that occur in Cassandra. */
  Convention CONVENTION = new Convention.Impl("SOLR", SolrRel.class);

  /** Callback for the implementation process that converts a tree of {@link SolrRel} nodes into a Solr query. */
  class Implementor {
    final List<String> selectFields = new ArrayList<>();
    final List<String> whereClause = new ArrayList<>();
    String limitValue = null;
    final List<String> order = new ArrayList<>();

    RelOptTable table;
    SolrTable solrTable;

    /** Adds newly projected fields and restricted predicates.
     *
     * @param fields New fields to be projected from a query
     * @param predicates New predicates to be applied to the query
     */
    public void add(List<String> fields, List<String> predicates) {
      if (fields != null) {
        selectFields.addAll(fields);
      }
      if (predicates != null) {
        whereClause.addAll(predicates);
      }
    }

    public void addOrder(List<String> newOrder) {
      order.addAll(newOrder);
    }

    public void setLimit(String limit) {
      limitValue = limit;
    }

    public void visitChild(int ordinal, RelNode input) {
      assert ordinal == 0;
      ((SolrRel) input).implement(this);
    }
  }
}
