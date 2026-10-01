package com.example.springbootbackend.groom;

import com.example.springbootbackend.horse.storage.HorseStorage;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/stable-incidents")
@PreAuthorize("isAuthenticated()")
public class StableIncidentImageController {
    private static final Set<String> IMAGE_ROLES=Set.of("GROOM","VETERINARIAN","CLUB_MANAGER");
    private final JdbcTemplate db;
    private final HorseStorage storage;

    public StableIncidentImageController(JdbcTemplate db,HorseStorage storage) {
        this.db=db;
        this.storage=storage;
    }

    @GetMapping("/{id}/image")
    public Map<String,Object> image(Principal principal,@PathVariable UUID id) {
        var accounts=db.queryForList("SELECT u.user_id,r.role_name FROM users u JOIN roles r ON r.role_id=u.role_id "
                +"WHERE lower(u.email)=lower(?) AND u.status='APPROVED' AND u.must_change_password=FALSE AND u.deleted_at IS NULL",
                principal.getName());
        if(accounts.isEmpty()) throw new ResponseStatusException(FORBIDDEN,"Active account required");
        Map<String,Object> account=accounts.getFirst();
        String role=(String)account.get("role_name");
        if(!IMAGE_ROLES.contains(role)) throw new ResponseStatusException(FORBIDDEN,"This role cannot view incident photos");
        List<Map<String,Object>> incidents=db.queryForList("SELECT groom_id,image_url FROM stable_incidents WHERE id=?",id.toString());
        if(incidents.isEmpty()) throw new ResponseStatusException(NOT_FOUND,"Incident not found");
        Map<String,Object> incident=incidents.getFirst();
        if("GROOM".equals(role) && (! (incident.get("groom_id") instanceof Number groomId)
                || groomId.longValue()!=((Number)account.get("user_id")).longValue()))
            throw new ResponseStatusException(FORBIDDEN,"Groom can only view photos from their own reports");
        Object path=incident.get("image_url");
        if(!(path instanceof String value) || !value.startsWith("incidents/")) return Map.of("hasImage",false);
        return storage.signedUrl(value);
    }
}
