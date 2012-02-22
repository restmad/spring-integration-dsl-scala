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

import org.springframework.context.ApplicationContext
import org.springframework.context.support.GenericApplicationContext
import org.springframework.integration.Message
import java.lang.reflect.Method
import org.apache.log4j.Logger
import org.springframework.util.StringUtils
import org.springframework.beans.factory.support.{ BeanDefinitionReaderUtils, BeanDefinitionBuilder }
import org.springframework.beans.factory.config.BeanDefinitionHolder
import org.springframework.integration.scheduling.PollerMetadata
import org.springframework.scheduling.support.PeriodicTrigger
import org.springframework.integration.context.IntegrationContextUtils
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy
import org.springframework.integration.channel._
import org.springframework.integration.router.{ PayloadTypeRouter, HeaderValueRouter }
import java.util.{ HashMap, UUID }
import java.lang.IllegalStateException
import org.springframework.integration.config._
import org.springframework.integration.aggregator.{ AggregatingMessageHandler, DefaultAggregatingMessageGroupProcessor }
import collection.JavaConversions
import org.springframework.integration.support.MessageBuilder
import org.springframework.integration.handler.BridgeHandler
import org.springframework.integration.transformer.HeaderEnricher
import org.springframework.integration.transformer.HeaderEnricher.StaticHeaderValueMessageProcessor
import org.springframework.integration.transformer.HeaderEnricher.HeaderValueMessageProcessor
import org.springframework.integration.transformer.MessageTransformingHandler

/**
 * @author Oleg Zhurakousky
 */
private[integration] object ApplicationContextBuilder {

  private val logger = Logger.getLogger(this.getClass)

  /**
   *
   */
  def build(parentContext: ApplicationContext,
    composition: BaseIntegrationComposition): ApplicationContext = {

    implicit val applicationContext = new GenericApplicationContext()

    if (parentContext != null) {
      applicationContext.setParent(parentContext)
    }
    //TODO make it conditional based on what may already be registered in parent
    this.preProcess(applicationContext)

    if (this.logger.isDebugEnabled())
      this.logger.debug("Initializing the following composition segment: " + DslUtils.toProductList(composition))

    this.init(composition, null)
   
    applicationContext.refresh()
    logger.info("\n*** Spring Integration Message Flow composition was initialized successfully ***\n")
    applicationContext
  }

  /**
   *
   */
  private def init(composition: BaseIntegrationComposition, outputChannel: AbstractChannel)(implicit applicationContext: GenericApplicationContext): Unit = {

    val inputChannel: AbstractChannel = this.determineInputChannel(composition)

    if (inputChannel != null) this.buildChannel(inputChannel)

    val nextOutputChannel: AbstractChannel = this.determineNextOutputChannel(composition, inputChannel)

    if (nextOutputChannel != null) this.buildChannel(nextOutputChannel)

    if (composition.parentComposition != null) {
      composition.target match {
        case channel: AbstractChannel => {
          composition.parentComposition.target match {
            case parentChannel: Channel => {
              if (logger.isTraceEnabled) {
                logger.trace("[" + inputChannel.name + " --> bridge --> " + composition.target.asInstanceOf[Channel].name + "]")
              }
              this.wireEndpoint(new MessagingBridge(), inputChannel, (if (outputChannel != null) outputChannel else null))
            }
            case _ =>
          }
        }
        case listComp: ListOfCompositions[BaseIntegrationComposition] => {
          for (comp <- listComp.compositions) {
            this.init(comp, null)
          }
        }
        case endpoint: IntegrationComponent => {
          composition.parentComposition.target match {
            case poller: Poller => {
              if (logger.isTraceEnabled) {
                logger.trace("[" + inputChannel.name + " --> Polling(" + composition.target + ")" +
                  (if (outputChannel != null) (" --> " + outputChannel.name) else "") + "]")
              }
              this.wireEndpoint(endpoint, inputChannel, (if (outputChannel != null) outputChannel else null), poller)
            }
            case _ => {
              if (logger.isTraceEnabled) {
                logger.trace("[" + inputChannel.name + " --> " + composition.target +
                  (if (outputChannel != null) (" --> " + outputChannel.name) else "") + "]")
              }
              if (!endpoint.isInstanceOf[Poller])
            	  this.wireEndpoint(endpoint, inputChannel, (if (outputChannel != null) outputChannel else null))
            }
          }
        }
        case _ =>
      }
    }

    if (composition.parentComposition != null) {
      this.init(composition.parentComposition, nextOutputChannel)
    }
  }

  private def determineInputChannel(composition: BaseIntegrationComposition): AbstractChannel = {

    val inputChannel: AbstractChannel = if (composition.parentComposition != null) {
      composition.parentComposition.target match {
        case ch: AbstractChannel => {
          ch
        }
        case poller: Poller => {
          composition.parentComposition.parentComposition.target.asInstanceOf[AbstractChannel]
        }
        case endpoint: IntegrationComponent => {
          if (!composition.target.isInstanceOf[AbstractChannel]) {
            Channel("$ch_" + UUID.randomUUID().toString.substring(0, 8))
          } else null
        }
        case _ => throw new IllegalStateException("Unrecognized component " + composition)
      }
    } else {
      null
    }
    inputChannel
  }
  /**
   *
   */
  private def determineNextOutputChannel(composition: BaseIntegrationComposition, previousInputChannel: AbstractChannel): AbstractChannel = {
    composition.target match {
      case ch: AbstractChannel => {
        ch
      }
      case _ => {
        previousInputChannel
      }
    }
  }

  private def buildChannel(channelDefinition: AbstractChannel)(implicit applicationContext: GenericApplicationContext): Unit = {
    val channelBuilder: BeanDefinitionBuilder =
      channelDefinition match {
        case ch: Channel => {
          if (ch.capacity == Integer.MIN_VALUE) { // DirectChannel
            BeanDefinitionBuilder.rootBeanDefinition(classOf[DirectChannel])
          } 
          else if (ch.capacity > Integer.MIN_VALUE) {
            val builder = BeanDefinitionBuilder.rootBeanDefinition(classOf[QueueChannel])
            builder.addConstructorArgValue(ch.capacity)
            builder
          } 
          else if (ch.taskExecutor != null) {
            val builder = BeanDefinitionBuilder.rootBeanDefinition(classOf[ExecutorChannel])
            builder.addConstructorArgValue(ch.taskExecutor)
            builder
          } 
          else throw new IllegalArgumentException("Unsupported Channel type: " + channelDefinition)
        }
        case _ => BeanDefinitionBuilder.rootBeanDefinition(classOf[PublishSubscribeChannel])
      }

    if (!applicationContext.containsBean(channelDefinition.name)) {
      if (logger.isDebugEnabled) logger.debug("Creating " + channelDefinition)
      
      applicationContext.registerBeanDefinition(channelDefinition.name, channelBuilder.getBeanDefinition)
    }

  }

  /**
   *
   */
  private def wireEndpoint(endpoint: IntegrationComponent, inputChannel: AbstractChannel, outputChannel: AbstractChannel, poller: Poller = null)(implicit applicationContext: GenericApplicationContext) {
    require(endpoint.name != null, "Each component must be named " + endpoint)
    
    if (!applicationContext.containsBean(endpoint.name)) {
      if (logger.isDebugEnabled) logger.debug("Creating " + endpoint)

      val consumerBuilder =
        BeanDefinitionBuilder.rootBeanDefinition(classOf[ConsumerEndpointFactoryBean])
      var handlerBuilder = this.getHandlerDefinitionBuilder(endpoint, outputChannel)

      consumerBuilder.addPropertyValue("inputChannelName", inputChannel.name)

      if (poller != null) {
        this.configurePoller(endpoint, poller, consumerBuilder)
      }

      if (outputChannel != null) {
        endpoint match {
          case rt: Router => {
            handlerBuilder.addPropertyReference("defaultOutputChannel", outputChannel.name);
          }
          case _ => {
            handlerBuilder.addPropertyReference("outputChannel", outputChannel.name);
          }
        }
      }

      consumerBuilder.addPropertyValue("handler", handlerBuilder.getBeanDefinition)
      val consumerName = endpoint.name
      if (StringUtils.hasText(consumerName)) BeanDefinitionReaderUtils.
          registerBeanDefinition(new BeanDefinitionHolder(consumerBuilder.getBeanDefinition, consumerName), applicationContext)
      else BeanDefinitionReaderUtils.registerWithGeneratedName(consumerBuilder.getBeanDefinition, applicationContext)
    }

  }

  /**
   *
   */
  private def configurePoller(endpoint: IntegrationComponent, pollerConfig: Poller, consumerBuilder: BeanDefinitionBuilder)(implicit applicationContext: GenericApplicationContext) = {
    if (logger.isDebugEnabled) logger debug "Creating Polling consumer using " + pollerConfig

    var pollerBuilder =
      BeanDefinitionBuilder.rootBeanDefinition(classOf[PollerMetadata])

    var triggerBuilder = BeanDefinitionBuilder.genericBeanDefinition(classOf[PeriodicTrigger])
    
    if (pollerConfig.fixedRate > Integer.MIN_VALUE) {
      triggerBuilder.addConstructorArgValue(pollerConfig.fixedRate);
      triggerBuilder.addPropertyValue("fixedRate", true);
    }

    val triggerBeanName = BeanDefinitionReaderUtils.registerWithGeneratedName(triggerBuilder.getBeanDefinition, applicationContext)
    pollerBuilder.addPropertyReference("trigger", triggerBeanName)

    if (pollerConfig.maxMessagesPerPoll > Integer.MIN_VALUE) {
      pollerBuilder.addPropertyValue("maxMessagesPerPoll", pollerConfig.maxMessagesPerPoll)
    }

    consumerBuilder.addPropertyValue("pollerMetadata", pollerBuilder.getBeanDefinition)
  }

  /**
   *
   */
  private def getHandlerDefinitionBuilder(endpoint: IntegrationComponent, outputChannel: AbstractChannel = null)(implicit applicationContext: GenericApplicationContext): BeanDefinitionBuilder = {
    var handlerBuilder: BeanDefinitionBuilder = null

    endpoint match {
      case sa: ServiceActivator => {
        handlerBuilder = BeanDefinitionBuilder.rootBeanDefinition(classOf[ServiceActivatorFactoryBean])
        this.defineHandlerTarget(sa, handlerBuilder)
      }
      case enricher: Enricher => {
        enricher.target match {
          case fn:Function1[_,AnyRef] => {
            
          }
          case tp:Tuple2[String, AnyRef] => {
            val map = new java.util.HashMap[String, HeaderValueMessageProcessor[_]]
            if (tp._2.isInstanceOf[Function[_,_]]){
                val clazz = Class.forName("org.springframework.integration.transformer.HeaderEnricher$MessageProcessingHeaderValueMessageProcessor")
                val functionInvoker = new FunctionInvoker(tp._2.asInstanceOf[Function[_, _]], endpoint)
                val const = clazz.getDeclaredConstructor(classOf[Any], classOf[String])
                const.setAccessible(true)
                val p = const.newInstance(functionInvoker, functionInvoker.methodName)
                map.put(tp._1, p.asInstanceOf[HeaderValueMessageProcessor[_]])
              }
              else if (tp._2.isInstanceOf[String] || tp._2.isInstanceOf[Option[_]]){
                val clazz = Class.forName("org.springframework.integration.transformer.HeaderEnricher$StaticHeaderValueMessageProcessor")
                val const = clazz.getDeclaredConstructor(classOf[Any])
	            const.setAccessible(true)
	            val p = const.newInstance(tp._2)
	            map.put(tp._1, p.asInstanceOf[HeaderValueMessageProcessor[_]])
              }
              else
                throw new RuntimeException("")
            
            
            handlerBuilder = BeanDefinitionBuilder.rootBeanDefinition(classOf[MessageTransformingHandler])
            val transformerBuilder = BeanDefinitionBuilder.rootBeanDefinition(classOf[HeaderEnricher])
            transformerBuilder.addConstructorArg(map)
            handlerBuilder.addConstructorArg(transformerBuilder.getBeanDefinition())
          }
        }
      }
      case xfmr: Transformer => {
        handlerBuilder = BeanDefinitionBuilder.rootBeanDefinition(classOf[TransformerFactoryBean])
        this.defineHandlerTarget(xfmr, handlerBuilder)
      }
      case mb: MessagingBridge => {
        handlerBuilder = BeanDefinitionBuilder.rootBeanDefinition(classOf[BridgeHandler])
      }
      case router: Router => {
        val conditions = router.conditions
        if (conditions.size > 0) {
          handlerBuilder = conditions(0) match {
            case hv: ValueCondition => {
              if (router.headerName != null) {
                if (logger.isDebugEnabled) logger.debug("Router is HeaderValueRouter")
                
                BeanDefinitionBuilder.rootBeanDefinition(classOf[HeaderValueRouter])
              } 
              else {
                if (logger.isDebugEnabled) logger.debug("Router is MethodInvoking")

                val hBuilder = BeanDefinitionBuilder.rootBeanDefinition(classOf[RouterFactoryBean])
                this.defineHandlerTarget(router, hBuilder)
                hBuilder
              }
            }
            case pt: PayloadTypeCondition => {
              if (logger.isDebugEnabled) logger.debug("Router is PayloadTypeRouter")

              BeanDefinitionBuilder.rootBeanDefinition(classOf[PayloadTypeRouter])
            }
            case _ => throw new IllegalStateException("Unrecognized Router type: " + conditions(0))
          }
        }

        if (StringUtils.hasText(router.headerName)) {
          handlerBuilder.addConstructorArgValue(router.headerName)
        }

        val channelMappings = new HashMap[Any, Any]()

        for (condition <- conditions) {
          val composition = condition.integrationComposition.copy()
          val normailizedCompositon = composition.normalizeComposition()
          val startingComposition = DslUtils.getStartingComposition(normailizedCompositon)
          startingComposition.target match {
            case ch: AbstractChannel => {
              channelMappings.put(condition.value, ch.name)
              this.init(normailizedCompositon, null)
            }
            case _ => throw new IllegalArgumentException("Starting composition is not a channel")
          }

        }
        handlerBuilder.addPropertyValue("channelMappings", channelMappings)
      }
      case fltr: MessageFilter => {
        handlerBuilder = BeanDefinitionBuilder.rootBeanDefinition(classOf[FilterFactoryBean])
        if (fltr.exceptionOnRejection) {
          handlerBuilder.addPropertyValue("throwExceptionOnRejection", fltr.exceptionOnRejection)
        }
        this.defineHandlerTarget(fltr, handlerBuilder)
      }
      case splitter: MessageSplitter => {
        handlerBuilder = BeanDefinitionBuilder.rootBeanDefinition(classOf[SplitterFactoryBean])
        this.defineHandlerTarget(splitter, handlerBuilder)
      }
      case aggregator: MessageAggregator => {
        handlerBuilder = BeanDefinitionBuilder.rootBeanDefinition(classOf[AggregatingMessageHandler])
        val processorBuilder = BeanDefinitionBuilder.genericBeanDefinition(classOf[DefaultAggregatingMessageGroupProcessor]);
        handlerBuilder.addConstructorArgValue(processorBuilder.getBeanDefinition());
      }
      case _ => throw new IllegalArgumentException("handler is not currently supported: " + endpoint)
    }
    handlerBuilder
  }

  /**
   *
   */
  private def defineHandlerTarget(endpoint: SimpleEndpoint, handlerBuilder: BeanDefinitionBuilder) = {

    endpoint.target match {
      case function: Function[_, _] => {
        val functionInvoker = new FunctionInvoker(function, endpoint)
        handlerBuilder.addPropertyValue("targetObject", functionInvoker);
        handlerBuilder.addPropertyValue("targetMethodName", functionInvoker.methodName);
      }
      case spel: String => {
        handlerBuilder.addPropertyValue("expressionString", spel);
      }
      case _ => {
        throw new IllegalArgumentException("Unsupported value for 'target' - " + endpoint.target)
      }
    }
  }

  private[dsl] final class FunctionInvoker(val f: Function[_, _], endpoint: IntegrationComponent) {
    private val logger = Logger.getLogger(this.getClass)
    var methodName: String = ""

    var method: Method = null
    val methods = f.getClass().getDeclaredMethods()
    if (methods.size > 1) {
      for (m <- f.getClass().getDeclaredMethods()) {
        var returnType = m.getReturnType()
        val inputParameter = m.getParameterTypes()(0)
        if (!(returnType.isAssignableFrom(classOf[Object]) && inputParameter.isAssignableFrom(classOf[Object]))) {
          if (logger.isDebugEnabled) {
            logger.debug("Selecting method: " + m)
          }
          method = m
          if (returnType.isAssignableFrom(Void.TYPE) && inputParameter.isAssignableFrom(classOf[Message[_]])) {
            methodName = "sendMessage"
          } 
          else if (returnType.isAssignableFrom(Void.TYPE) && !inputParameter.isAssignableFrom(classOf[Message[_]])) {
            methodName = "sendPayload"
          } 
          else if (returnType.isAssignableFrom(classOf[Message[_]]) && inputParameter.isAssignableFrom(classOf[Message[_]])) {
            methodName = "sendMessageAndReceiveMessage"
          } 
          else if (!returnType.isAssignableFrom(classOf[Message[_]]) && inputParameter.isAssignableFrom(classOf[Message[_]])) {
            methodName = "sendMessageAndReceivePayload"
          } 
          else if (returnType.isAssignableFrom(classOf[Message[_]]) && !inputParameter.isAssignableFrom(classOf[Message[_]])) {
            methodName = "sendPayloadAndReceiveMessage"
          } 
          else if (!returnType.isAssignableFrom(classOf[Message[_]]) && !inputParameter.isAssignableFrom(classOf[Message[_]])) {
            methodName = "sendPayloadAndReceivePayload"
          }
        }
      }
    } else {
      method = f.getClass.getDeclaredMethod("apply", classOf[Object])
      methodName = "sendPayoadAndReceive"
      if (logger.isDebugEnabled) {
        logger.debug("Selecting method: " + method)
      }
    }
    if (logger.isDebugEnabled) {
      logger.debug("FunctionInvoker method name: " + methodName)
    }
    def sendPayload(m: Object): Unit = {
      method.setAccessible(true)
      method.invoke(f, m)
    }
    def sendMessage(m: Message[_]): Unit = {
      method.setAccessible(true)
      method.invoke(f, m)
    }
    def sendPayloadAndReceivePayload(m: Object): Object = {
      var method = f.getClass.getDeclaredMethod("apply", classOf[Any])
      method.setAccessible(true)
      this.normalizeResult[Object](method.invoke(f, m))
    }
    def sendPayloadAndReceiveMessage(m: Object): Message[_] = {
      var method = f.getClass.getDeclaredMethod("apply", classOf[Any])
      method.setAccessible(true)
      this.normalizeResult[Message[_]](method.invoke(f, m).asInstanceOf[Message[_]])
    }
    def sendMessageAndReceivePayload(m: Message[_]): Object = {
      var method = f.getClass.getDeclaredMethod("apply", classOf[Any])
      method.setAccessible(true)
      this.normalizeResult[Object](method.invoke(f, m))
    }
    def sendMessageAndReceiveMessage(m: Message[_]): Message[_] = {
      var method = f.getClass.getDeclaredMethod("apply", classOf[Any])
      method.setAccessible(true)

      this.normalizeResult[Message[_]](method.invoke(f, m).asInstanceOf[Message[_]])
    }

    private def normalizeResult[T](result: Any): T = {
      endpoint match {
        case splitter: MessageSplitter => {
          result match {
            case message: Message[_] => {
              val payload = message.getPayload
              if (payload.isInstanceOf[Iterable[_]]) {
                MessageBuilder.withPayload(JavaConversions.asJavaCollection(payload.asInstanceOf[Iterable[_]])).
                  copyHeaders(message.getHeaders).build().asInstanceOf[T]
              } else {
                message.asInstanceOf[T]
              }
            }
            case _ => {
              if (result.isInstanceOf[Iterable[_]]) {
                JavaConversions.asJavaCollection(result.asInstanceOf[Iterable[_]]).asInstanceOf[T]
              } else {
                result.asInstanceOf[T]
              }
            }
          }
        }
        case _ => {
          result.asInstanceOf[T]
        }
      }

    }
  }

  private def preProcess(applicationContext: GenericApplicationContext) {

    // taskScheduler
    var schedulerBuilder = BeanDefinitionBuilder
      .genericBeanDefinition(classOf[ThreadPoolTaskScheduler]);
    schedulerBuilder.addPropertyValue("poolSize", 10);
    schedulerBuilder.addPropertyValue("threadNamePrefix", "task-scheduler-");
    schedulerBuilder.addPropertyValue("rejectedExecutionHandler", new CallerRunsPolicy());
    var errorHandlerBuilder = BeanDefinitionBuilder.genericBeanDefinition(classOf[MessagePublishingErrorHandler]);
    errorHandlerBuilder.addPropertyReference("defaultErrorChannel", "errorChannel");
    schedulerBuilder.addPropertyValue("errorHandler", errorHandlerBuilder.getBeanDefinition());

    applicationContext.registerBeanDefinition(IntegrationContextUtils.TASK_SCHEDULER_BEAN_NAME, schedulerBuilder.getBeanDefinition)

    // default errorChannel
    var errorChannelBuilder =
      BeanDefinitionBuilder.rootBeanDefinition(classOf[PublishSubscribeChannel])
    applicationContext.registerBeanDefinition(IntegrationContextUtils.ERROR_CHANNEL_BEAN_NAME, errorChannelBuilder.getBeanDefinition)
  }
}

