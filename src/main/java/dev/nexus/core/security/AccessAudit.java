package dev.nexus.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE+10)
public class AccessAudit extends OncePerRequestFilter {
    private final JdbcTemplate db;
    private final org.springframework.transaction.support.TransactionTemplate transaction;
    private final System.Logger log=System.getLogger(AccessAudit.class.getName());
    public AccessAudit(JdbcTemplate db,org.springframework.transaction.support.TransactionTemplate transaction){this.db=db;this.transaction=transaction;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        String path=request.getServletPath(),method=request.getMethod();
        boolean audit=path.startsWith("/api/v1/")&&(!method.equals("GET")&&!method.equals("HEAD") || path.endsWith("/export"));
        if(!audit){chain.doFilter(request,response);return;}
        UUID id=UUID.randomUUID();String target=path.replaceAll("[^a-zA-Z0-9_./-]","?");
        if(target.length()>256)target=target.substring(0,256);
        try{db.update("INSERT INTO access_audit(audit_id,method,target) VALUES(?,?,?)",id,method,target);}
        catch(RuntimeException unavailable){response.setStatus(503);response.setContentType("application/json");response.getWriter().write("{\"error\":\"audit_unavailable\"}");return;}
        boolean failed=false;
        try{chain.doFilter(request,response);}catch(ServletException|IOException|RuntimeException error){failed=true;throw error;}
        finally{
            String role=String.valueOf(request.getAttribute("nexus.role")),actor=String.valueOf(request.getAttribute("nexus.actor"));
            try{db.update("UPDATE access_audit SET actor=?,role=?,status=?,completed_at=now() WHERE audit_id=?",actor.equals("null")?"anonymous":actor,role.equals("null")?"anonymous":role,failed?500:response.getStatus(),id);}
            catch(RuntimeException unavailable){log.log(System.Logger.Level.ERROR,"Audit completion unavailable; original request outcome requires review");}
        }
    }
    public Map<String,Object> list(int page,String role){
        String where=role==null?"":" WHERE role=?";Object[] args=role==null?new Object[]{100,(page-1)*100}:new Object[]{role,100,(page-1)*100};
        var items=dev.nexus.core.web.ApiRows.normalize(db.queryForList("SELECT audit_id AS \"auditId\",actor,role,method,target,status,created_at AS \"createdAt\",completed_at AS \"completedAt\" FROM access_audit"+where+" ORDER BY created_at DESC,audit_id LIMIT ? OFFSET ?",args));
        Long total=db.queryForObject("SELECT count(*) FROM access_audit"+where,Long.class,role==null?new Object[]{}:new Object[]{role});
        return Map.of("items",items,"total",total,"page",page,"size",100);
    }
    public Map<String,Object> retention(){return Map.of("days",90,"batchLimit",200,"eligible",db.queryForObject("SELECT count(*) FROM access_audit WHERE created_at<now()-interval '90 days' AND completed_at IS NOT NULL",Long.class));}
    public Map<String,Object> prune(String confirm){
        if(!"DELETE_EXPIRED_AUDIT".equals(confirm))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"invalid_request");
        return transaction.execute(status->Map.of("deleted",db.update("DELETE FROM access_audit WHERE audit_id IN (SELECT audit_id FROM access_audit WHERE created_at<now()-interval '90 days' AND completed_at IS NOT NULL ORDER BY created_at LIMIT 200 FOR UPDATE SKIP LOCKED)")));
    }
}
