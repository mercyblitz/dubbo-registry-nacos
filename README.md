# dubbo-registry-nacos

`dubbo-registry-nacos` is a [Dubbo](https://github.com/apache/incubator-dubbo)'s registry implementation integrating with
 [Nacos](https://github.com/alibaba/nacos) that is service registry server.  




## Prerequisite

Before you integrate `dubbo-registry-nacos` into your Dubbo project, you need to start a Nacos server in the backend. 
Refer to [Nacos Quick Start](https://nacos.io/en-us/docs/quick-start.html) for instructions on how to 
start a Nacos server.




## Getting started




### Maven dependency

```xml
<dependencies>

    ...
    
    <dependency>
       <groupId>org.springframework</groupId>
       <artifactId>spring-context</artifactId>
       <version>[3.2.18.RELEASE,)</version>
   </dependency>
    
    <!-- Dubbo dependency -->
    <dependency>
        <groupId>com.alibaba</groupId>
        <artifactId>dubbo</artifactId>
        <version>2.6.5</version>
    </dependency>
    
    <!-- Dubbo Nacos registry dependency -->
    <dependency>
        <groupId>com.alibaba</groupId>
        <artifactId>dubbo-registry-nacos</artifactId>
        <version>0.0.2</version>
    </dependency>   
    
    <!-- Keep latest Nacos client version -->
    <dependency>
        <groupId>com.alibaba.nacos</groupId>
        <artifactId>nacos-client</artifactId>
        <version>[0.6.1,)</version>
    </dependency>
    
    ...
    
</dependencies>
```


### Define service interface

```java
package com.alibaba.dubbo.demo.service;

public interface DemoService {
    String sayName(String name);
}
```


### Implement service interface for the provider

```java
package com.alibaba.dubbo.demo.service;

@Service(version = "${demo.service.version}")
public class DefaultService implements DemoService {

    @Value("${demo.service.name}")
    private String serviceName;

    public String sayName(String name) {
        RpcContext rpcContext = RpcContext.getContext();
        return String.format("Service [name :%s , port : %d] %s(\"%s\") : Hello,%s",
                serviceName,
                rpcContext.getLocalPort(),
                rpcContext.getMethodName(),
                name,
                name);
    }
}
```


### Define service provider's configuration

```properties
## application
dubbo.application.name = dubbo-provider-demo

## Nacos registry address
dubbo.registry.address = nacos://127.0.0.1:8848

## Dubbo Protocol
dubbo.protocol.name = dubbo
dubbo.protocol.port = -1

# Provider @Service version
demo.service.version=1.0.0
demo.service.name = demoService
```




### Start service provider

```java
package com.alibaba.dubbo.demo.provider;

@EnableDubbo(scanBasePackages = "com.alibaba.dubbo.demo.service")
@PropertySource(value = "classpath:/provider-config.properties")
public class DemoServiceProviderBootstrap {

    public static void main(String[] args) throws IOException {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(DemoServiceProviderBootstrap.class);
        context.refresh();
        System.out.println("DemoService provider starting...");
        System.in.read();
    }
}
```


See [`DemoServiceProviderBootstrap.java`](src/test/java/com/alibaba/dubbo/demo/provider/DemoServiceProviderBootstrap.java) on GitHub.




### Define service consumer's configuration

```properties
## Dubbo Application info
dubbo.application.name = dubbo-consumer-demo

## Nacos registry address
dubbo.registry.address = nacos://127.0.0.1:8848

# @Reference version
demo.service.version= 1.0.0
```




### Start service consumer

```java
package com.alibaba.dubbo.demo.consumer;

@EnableDubbo
@PropertySource(value = "classpath:/consumer-config.properties")
public class DemoServiceConsumerBootstrap {

    @Reference(version = "${demo.service.version}")
    private DemoService demoService;

    @PostConstruct
    public void init() {
        for (int i = 0; i < 10; i++) {
            System.out.println(demoService.sayName("Mercy"));
        }
    }

    public static void main(String[] args) throws IOException {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(DemoServiceConsumerBootstrap.class);
        context.refresh();
        context.close();
    }
}
```

See [`DemoServiceConsumerBootstrap.java`](src/test/java/com/alibaba/dubbo/demo/consumer/DemoServiceConsumerBootstrap.java) on GitHub.
