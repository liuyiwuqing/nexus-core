package dev.nexus.core.connector;

import dev.nexus.core.inbox.InboxService;
import dev.nexus.core.inbox.MessageInput;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class PullConnectors {
    record Feed(String id,String name,String kind,URI url,int minutes,List<String> devices,String configuration) {}
    private final List<Feed> feeds;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final InboxService inbox;
    private final TransactionTemplate tx;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ConcurrentHashMap<String,Instant> probes=new ConcurrentHashMap<>();
    private final AtomicBoolean scheduledBusy=new AtomicBoolean();
    private final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("pull-connectors").factory());

    public PullConnectors(JdbcTemplate db,ObjectMapper json,InboxService inbox,TransactionTemplate tx,
        @Value("${nexus.pull.feeds-json:[]}") String configured,
        @Value("${nexus.pull.allow-insecure:false}") boolean insecure,
        @Value("${nexus.pull.weather-base-url:https://api.open-meteo.com/v1/forecast}") String weatherBase) {
        this.db=db;this.json=json;this.inbox=inbox;this.tx=tx;
        var parsed=new ArrayList<Feed>();
        try {
            var values=json.readTree(configured);
            if(!values.isArray()||values.size()>16)throw new IllegalArgumentException();
            for(var value:values) {
                String id=value.path("id").asText(),name=value.path("name").asText(),kind=value.path("kind").asText();
                int minutes=value.path("intervalMinutes").asInt();var devices=new ArrayList<String>();
                if(!id.matches("[a-z0-9][a-z0-9-]{0,23}")||name.isBlank()||name.length()>80||!List.of("rss","weather").contains(kind)
                    ||!value.path("intervalMinutes").isIntegralNumber()||minutes<(kind.equals("weather")?60:15)||minutes>1440||!value.path("deviceIds").isArray())throw new IllegalArgumentException();
                value.path("deviceIds").forEach(d->{if(!d.isString()||!d.asText().matches("[a-zA-Z0-9_-]{1,40}"))throw new IllegalArgumentException();devices.add(d.asText());});
                if(devices.isEmpty()||devices.size()>32||parsed.stream().anyMatch(f->f.id().equals(id)))throw new IllegalArgumentException();
                String address=value.path("url").asText();
                if(kind.equals("weather")) {
                    double latitude=value.path("latitude").asDouble(Double.NaN),longitude=value.path("longitude").asDouble(Double.NaN);
                    if(!value.path("latitude").isNumber()||!value.path("longitude").isNumber()||!Double.isFinite(latitude)||!Double.isFinite(longitude)||Math.abs(latitude)>90||Math.abs(longitude)>180)throw new IllegalArgumentException();
                    URI base=URI.create(weatherBase);if(base.getQuery()!=null)throw new IllegalArgumentException();
                    address=weatherBase+"?latitude="+latitude+"&longitude="+longitude+"&current=temperature_2m,relative_humidity_2m,weather_code&timeformat=unixtime&timezone=UTC";
                }
                URI url=URI.create(address);
                if(address.length()>2048||!("https".equals(url.getScheme())||insecure&&"http".equals(url.getScheme()))||url.getHost()==null||url.getUserInfo()!=null||url.getFragment()!=null)throw new IllegalArgumentException();
                var sorted=devices.stream().distinct().sorted().toList();
                String hash=hash(json.writeValueAsString(List.of(id,name,kind,address,minutes,sorted)));
                parsed.add(new Feed(id,name,kind,url,minutes,sorted,hash));
            }
        }catch(Exception invalid){throw new IllegalArgumentException("invalid_pull_connector_configuration");}
        feeds=List.copyOf(parsed);
    }
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private Feed feed(String id){return feeds.stream().filter(f->f.id().equals(id)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));}
    private Map<String,Object> state(Feed feed){var rows=db.queryForList("SELECT enabled,version,baseline_at,last_attempt_at,last_success_at,last_error,last_received,next_poll_at,lease_id,lease_until FROM pull_connector_state WHERE connector_id=? AND config_hash=?",feed.id(),feed.configuration());return rows.isEmpty()?Map.of("enabled",false,"version",1):rows.getFirst();}
    private Map<String,Object> view(Feed feed){
        var state=state(feed);var result=new LinkedHashMap<String,Object>();
        result.put("id",feed.id());result.put("name",feed.name());result.put("kind",feed.kind());result.put("host",feed.url().getHost());result.put("deviceIds",feed.devices());result.put("intervalMinutes",feed.minutes());result.put("configuration",feed.configuration());
        result.put("enabled",state.get("enabled"));result.put("version",state.get("version"));result.put("baseline",state.get("baseline_at")!=null);
        result.put("lastAttemptAt",state.get("last_attempt_at"));result.put("lastSuccessAt",state.get("last_success_at"));result.put("lastError",state.get("last_error"));result.put("lastReceived",state.getOrDefault("last_received",0));
        result.put("state",!Boolean.TRUE.equals(state.get("enabled"))?"disabled":state.get("last_error")!=null?"error":state.get("last_success_at")==null?"not_checked":"ready");
        return dev.nexus.core.web.ApiRows.normalize(List.of(result)).getFirst();
    }
    public List<Map<String,Object>> list(){return feeds.stream().map(this::view).toList();}
    private void ensure(Feed feed){db.update("INSERT INTO pull_connector_state(connector_id,config_hash) VALUES(?,?) ON CONFLICT DO NOTHING",feed.id(),feed.configuration());}
    private Map<String,Object> lock(Feed feed){db.queryForList("SELECT connector_id FROM pull_connector_state WHERE connector_id=? AND config_hash=? FOR UPDATE",feed.id(),feed.configuration());return state(feed);}
    private void configuration(Feed feed,String expected){if(!feed.configuration().equals(expected))throw new ResponseStatusException(HttpStatus.CONFLICT,"pull_configuration_changed");}
    public Map<String,Object> enabled(String id,String configuration,int version,boolean enabled){
        Feed feed=feed(id);configuration(feed,configuration);
        if(enabled)for(String device:feed.devices())if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM devices WHERE device_id=?)",Boolean.class,device)))throw new ResponseStatusException(HttpStatus.CONFLICT,"pull_devices_missing");
        return tx.execute(s->{ensure(feed);var current=lock(feed);int actual=(Integer)current.get("version");
            if(actual!=version&&!(actual==(long)version+1&&Boolean.valueOf(enabled).equals(current.get("enabled"))))throw new ResponseStatusException(HttpStatus.CONFLICT,"pull_version_conflict");
            if(!Boolean.valueOf(enabled).equals(current.get("enabled")))db.update("UPDATE pull_connector_state SET enabled=?,version=version+1,lease_id=NULL,lease_until=NULL WHERE connector_id=? AND config_hash=?",enabled,id,configuration);
            return view(feed);});
    }
    public Map<String,Object> probe(String id){
        Feed feed=feed(id);Instant now=Instant.now();
        synchronized(probes){if(probes.containsKey(id)&&probes.get(id).plusSeconds(60).isAfter(now))throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"pull_rate_limited");probes.put(id,now);}
        var items=fetch(feed);
        return Map.of("dryRun",true,"items",items.size(),"samples",items.stream().limit(3).map(i->Map.of("title",i.title(),"content",i.content())).toList());
    }
    public Map<String,Object> poll(String id,String configuration,int version){
        Feed feed=feed(id);configuration(feed,configuration);UUID lease=UUID.randomUUID();
        tx.executeWithoutResult(s->{ensure(feed);var current=lock(feed);
            if(!Integer.valueOf(version).equals(current.get("version")))throw new ResponseStatusException(HttpStatus.CONFLICT,"pull_version_conflict");
            if(!Boolean.TRUE.equals(current.get("enabled")))throw new ResponseStatusException(HttpStatus.CONFLICT,"pull_disabled");
            Instant now=Instant.now();
            if(current.get("lease_until") instanceof Timestamp until&&until.toInstant().isAfter(now))throw new ResponseStatusException(HttpStatus.CONFLICT,"pull_in_progress");
            if(current.get("last_attempt_at") instanceof Timestamp last&&last.toInstant().plusSeconds(60).isAfter(now))throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"pull_rate_limited");
            db.update("UPDATE pull_connector_state SET lease_id=?,lease_until=now()+interval '30 seconds',last_attempt_at=now(),next_poll_at=now()+(?*interval '1 minute') WHERE connector_id=? AND config_hash=?",lease,feed.minutes(),id,configuration);
        });
        try {
            var items=fetch(feed);
            return tx.execute(s->{var current=lock(feed);
                if(!lease.equals(current.get("lease_id"))||!Boolean.TRUE.equals(current.get("enabled"))||!Integer.valueOf(version).equals(current.get("version")))throw new ResponseStatusException(HttpStatus.CONFLICT,"pull_configuration_changed");
                boolean baseline=current.get("baseline_at")==null;int received=0;
                int history=db.queryForObject("SELECT count(*) FROM pull_connector_seen WHERE connector_id=? AND config_hash=?",Integer.class,id,configuration);
                for(var item:items){String key=hash(item.key());
                    if(db.update("INSERT INTO pull_connector_seen(connector_id,config_hash,event_hash) VALUES(?,?,?) ON CONFLICT DO NOTHING",id,configuration,key)==0)continue;
                    if(++history>20000)throw new ResponseStatusException(HttpStatus.CONFLICT,"pull_history_limit");
                    if(baseline||item.published()!=null&&item.published().isBefore(((Timestamp)current.get("baseline_at")).toInstant()))continue;
                    var message=new MessageInput("pull-"+id,feed.kind()+"."+id,hash(feed.url().toString()+"\n"+item.key()),feed.kind().equals("rss")?"feed.item":"weather.updated",item.title(),item.content(),null,feed.name(),item.url(),"normal",null,null,Map.of("provider",feed.kind(),"pullId",id),null,null);
                    if(inbox.ingestScoped(message,feed.devices()).created())received++;
                }
                db.update("UPDATE pull_connector_state SET baseline_at=COALESCE(baseline_at,now()),last_success_at=now(),last_error=NULL,last_received=?,lease_id=NULL,lease_until=NULL WHERE connector_id=? AND config_hash=?",received,id,configuration);
                return Map.<String,Object>of("baseline",baseline,"received",received);
            });
        }catch(RuntimeException error){
            String reason=error instanceof ResponseStatusException response&&response.getReason()!=null?response.getReason():"pull_failed";
            db.update("UPDATE pull_connector_state SET last_error=?,lease_id=NULL,lease_until=NULL WHERE connector_id=? AND config_hash=? AND lease_id=?",reason,id,configuration,lease);
            throw error;
        }
    }
    private List<PullContent.Item> fetch(Feed feed){
        try {
            var request=HttpRequest.newBuilder(feed.url()).timeout(Duration.ofSeconds(6)).header("Accept",feed.kind().equals("rss")?"application/rss+xml, application/atom+xml, application/xml, text/xml":"application/json").GET().build();
            var response=http.send(request,HttpBodies.limited(2*1024*1024));
            if(response.statusCode()!=200)throw new IllegalArgumentException();
            String type=response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].strip().toLowerCase(java.util.Locale.ROOT);
            if(!(feed.kind().equals("rss")?List.of("application/rss+xml","application/atom+xml","application/xml","text/xml").contains(type):type.equals("application/json")))throw new IllegalArgumentException();
            return feed.kind().equals("rss")?PullContent.rss(response.body()):PullContent.weather(response.body(),json,feed.name());
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"pull_interrupted");}
        catch(Exception failure){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"pull_provider_failed");}
    }
    @Scheduled(fixedDelayString="${nexus.pull.tick-ms:30000}",initialDelayString="${nexus.pull.tick-ms:30000}")
    void scheduled(){
        if(!scheduledBusy.compareAndSet(false,true))return;
        worker.execute(()->{try{feeds.stream().filter(f->{var s=state(f);return Boolean.TRUE.equals(s.get("enabled"))&&s.get("next_poll_at") instanceof Timestamp next&&!next.toInstant().isAfter(Instant.now());})
            .sorted(java.util.Comparator.comparing(f->((Timestamp)state(f).get("next_poll_at")).toInstant())).findFirst().ifPresent(f->{var s=state(f);try{poll(f.id(),f.configuration(),(Integer)s.get("version"));}catch(RuntimeException ignored){/* Sanitized health is persisted; never log provider URLs or payloads. */}});
        }finally{scheduledBusy.set(false);}});
    }
    @PreDestroy void close(){worker.shutdownNow();}
}
