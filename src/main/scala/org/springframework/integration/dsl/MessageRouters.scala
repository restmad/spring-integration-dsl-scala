/*
 * Copyright 2002-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.integration.dsl
import java.util.UUID
import org.springframework.integration.dsl.utils.DslUtils
import org.springframework.util.StringUtils

/**
 * @author Oleg Zhurakousky
 */
object route {

  def onPayloadType(conditionCompositions:PayloadTypeCondition*) = new SendingEndpointComposition(null,  new Router()(conditionCompositions: _*)) { 

    def where(name:String) = new SendingEndpointComposition(null, new Router(name, null, null)(conditionCompositions: _*))
  }

  def onValueOfHeader(headerName: String)(conditionCompositions: ValueCondition*) = {
    require(StringUtils.hasText(headerName), "'headerName' must not be empty")
    new SendingEndpointComposition(null, new Router(headerName = headerName)(conditionCompositions: _*)) {

      def where(name: String) = new SendingEndpointComposition(null, new Router(name = name, headerName = headerName)(conditionCompositions: _*))
    }
  }

  def using(target: String)(conditions: ValueCondition* ) =
    new SendingEndpointComposition(null, new Router(target = target)(conditions: _*))  {
      def where(name: String) = new SendingEndpointComposition(null, new Router(name = name, target = target)(conditions: _*))
    }

  def using(target: Function1[_, String])(conditions: ValueCondition*) =
    new SendingEndpointComposition(null, new Router(target = target)(conditions: _*)) {
      def where(name: String) = new SendingEndpointComposition(null, new Router(name = name, target = target)(conditions: _*))
    }
}
/**
 * 
 */
object when {
  def apply(payloadType:Class[_]) = new {
    def then(composition:BaseIntegrationComposition) = new PayloadTypeCondition(payloadType, composition)
  }
 
  def apply(value:Any) = new  {
    def then(composition:BaseIntegrationComposition) = new ValueCondition(value, composition)
  } 
}


private[dsl] class Router(name:String = "$rtr_" + UUID.randomUUID().toString.substring(0, 8), target:Any = null, val headerName:String = null)( val conditions:Condition*)
            extends SimpleEndpoint(name, target)

private[dsl] abstract class Condition(val value:Any, val integrationComposition:BaseIntegrationComposition)

private[dsl] class PayloadTypeCondition(val payloadType:Class[_], override val integrationComposition:BaseIntegrationComposition) 
	extends Condition(DslUtils.toJavaType(payloadType).getName(), integrationComposition)

private[dsl] class ValueCondition(override val value:Any, override val integrationComposition:BaseIntegrationComposition) 
	extends Condition(value, integrationComposition)