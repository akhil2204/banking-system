package com.banking.servicediscovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/*
 * @EnableEurekaServer does three things:
 *   1. Activates the Eureka registry — services POST to /eureka/apps to register
 *      and send heartbeats every 30 s to renew their lease.
 *   2. Starts the peer-replication loop — in a multi-instance HA setup, Eureka
 *      servers sync their registries with each other. We run one instance here,
 *      so peer replication is a no-op, but the code path is live.
 *   3. Mounts the Eureka dashboard at / — shows every registered service,
 *      its instance ID, status, and last heartbeat time.
 */
@SpringBootApplication
@EnableEurekaServer
public class ServiceDiscoveryApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceDiscoveryApplication.class, args);
    }
}
