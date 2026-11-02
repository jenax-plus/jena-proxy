/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 *   SPDX-License-Identifier: Apache-2.0
 */

import java.util.*;
import org.apache.jena.query.*;
import org.apache.jena.sparql.core.*;
import org.apache.jena.sparql.exec.*;
import org.apache.jena.sparql.util.*;
public class DistinctSpillOrder {
 public static void main(String[]args) {
  String q="SELECT DISTINCT ?p { VALUES (?s ?p) { (1 30) (2 20) (3 10) } } ORDER BY ?s";
  for(long threshold:new long[]{Long.MAX_VALUE,1}) {
   var data=DatasetGraphFactory.create();var context=Context.create();context.set(ARQ.spillToDiskThreshold,threshold);
   List<String> result=new ArrayList<>();
   try(var exec=QueryExec.dataset(data).query(q).context(context).build()) {
    var rows=exec.select();while(rows.hasNext())result.add(rows.next().get(Var.alloc("p")).getLiteralLexicalForm());
   }finally{data.close();}
   System.out.println("threshold="+threshold+" result="+result);
   if (!result.equals(List.of("30", "20", "10")))
    throw new AssertionError("DISTINCT changed input order when spilling: " + result);
  }
 }
}
