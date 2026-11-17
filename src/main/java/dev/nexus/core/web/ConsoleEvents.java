package dev.nexus.core.web;

import dev.nexus.core.delivery.MqttTransport;
import dev.nexus.core.security.RoleTokens;
import dev.nexus.core.security.BrowserSessions;
import dev.nexus.core.security.TokenCredentials;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class ConsoleEvents {
    private record Client(SseEmitter emitter,String authorization,BrowserSessions.Session session){}
    private final Set<Client> clients=ConcurrentHashMap.newKeySet();
    private final JdbcTemplate db;private final MqttTransport mqtt;private final TokenCredentials admin;private final RoleTokens roles;
    private final BrowserSessions sessions;
    private String revision="";
    public ConsoleEvents(JdbcTemplate db,MqttTransport mqtt,TokenCredentials admin,RoleTokens roles,BrowserSessions sessions){this.sessions=sessions;this.db=db;this.mqtt=mqtt;this.admin=admin;this.roles=roles;}
    public synchronized SseEmitter connect(String authorization,BrowserSessions.Session session){
        if(clients.size()>=32)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"event_capacity_reached");
        var emitter=new SseEmitter(25000L);var client=new Client(emitter,authorization,session);clients.add(client);
        emitter.onCompletion(()->clients.remove(client));emitter.onTimeout(()->{clients.remove(client);emitter.complete();});emitter.onError(e->clients.remove(client));
        try{emitter.send(SseEmitter.event().name("ready").data(Map.of("schemaVersion",1,"revision",snapshot())));}catch(IOException failure){clients.remove(client);emitter.complete();}
        return emitter;
    }
    private String snapshot(){
        // This is a change hint for the five root read models, not an event log.
        // Committed row values are observed in one snapshot; rolled-back writes
        // do not advance the hint before their data becomes readable.
        String state=db.queryForObject("""
            SELECT md5(jsonb_build_array(
                (SELECT jsonb_agg(jsonb_build_array(device_id,name,enabled,online,last_seen) ORDER BY device_id) FROM devices),
                (SELECT jsonb_build_array(count(*),max(received_at),count(read_at),max(read_at),count(content_purged_at)) FROM inbox_messages),
                (SELECT max(sequence) FROM delivery_history)
            )::text)
            """,String.class);
        return state+":"+mqtt.ready();
    }
    @Scheduled(fixedDelay=2000,initialDelay=2000)
    void update(){
        if(clients.isEmpty())return;
        String next;
        try{next=snapshot();}catch(RuntimeException unavailable){clients.forEach(c->{c.emitter().complete();clients.remove(c);});return;}
        boolean changed=!next.equals(revision);revision=next;
        for(var client:clients)try{
            if(client.session()!=null&&!sessions.active(client.session())||!admin.acceptsAdmin(client.authorization())&&roles.authenticate(client.authorization())==null){client.emitter().send(SseEmitter.event().name("unauthorized").data(Map.of("schemaVersion",1)));client.emitter().complete();clients.remove(client);continue;}
            client.emitter().send(SseEmitter.event().name(changed?"invalidate":"heartbeat").id(next).data(Map.of("schemaVersion",1,"revision",next)));
        }catch(IOException|RuntimeException disconnected){client.emitter().complete();clients.remove(client);}
    }
}
