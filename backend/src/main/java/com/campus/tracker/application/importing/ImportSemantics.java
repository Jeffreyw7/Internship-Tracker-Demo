package com.campus.tracker.application.importing;

import com.campus.tracker.application.*;
import com.campus.tracker.common.ApiException;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.*;
import java.time.format.*;
import java.util.*;
import static com.campus.tracker.application.ApplicationDtos.*;
import static com.campus.tracker.application.importing.ImportDtos.*;

/** Projects literal source values onto explicit editable-field intents, never onto persistence entities. */
@Component
public class ImportSemantics {
    static final List<String> EDITABLE = List.of("company","role","location","status","appliedDate","deadline","followUpDate","notes","stages");
    private static final Set<String> OPTIONAL = Set.of("location","notes","appliedDate","deadline","followUpDate");
    private static final Set<String> DESTINATIONS = Set.of("company","role","location","status","appliedDate","deadline","followUpDate","notes","stages","applicationId","legacyStages","ignore");
    private final ApplicationRules rules;
    private final ObjectMapper json;
    private final ImportInputLimits limits;
    public ImportSemantics(ApplicationRules rules,ObjectMapper mapper) { this(rules,mapper,new ImportInputLimits()); }
    @Autowired
    public ImportSemantics(ApplicationRules rules,ObjectMapper mapper,ImportInputLimits limits) {
        this.limits=limits;
        this.rules=rules; this.json=mapper.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        json.getFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(65536).build());
    }

    public Options validateOptions(Options input,int columns,int rowCount) {
        if(input==null || input.schemaVersion()!=1 || input.mapping()==null || input.rows()==null || input.dateFormat()==null || !Set.of("ISO","MDY","DMY").contains(input.dateFormat())) invalid();
        Set<String> destinations=new HashSet<>();
        for(var entry:input.mapping().entrySet()) {
            if(!entry.getKey().matches("0|[1-9][0-9]?")) invalid();
            int column=Integer.parseInt(entry.getKey());
            if(column>=columns || entry.getValue()==null || !DESTINATIONS.contains(entry.getValue()) || (!entry.getValue().equals("ignore") && !destinations.add(entry.getValue()))) invalid();
        }
        Set<Integer> rows=new HashSet<>(); List<RowOverride> normalized=new ArrayList<>();
        for(RowOverride row:input.rows()) {
            if(row==null || row.rowNumber()<1 || row.rowNumber()>rowCount || !rows.add(row.rowNumber())) invalid();
            if(row.action()!=null && !Set.of("Add","Update","Skip").contains(row.action())) invalid();
            if(row.stageMode()!=null && !Set.of("KEEP","REPLACE").contains(row.stageMode())) invalid();
            Map<String,String> values=row.values()==null?Map.of():row.values();
            List<String> clears=row.clears()==null?List.of():row.clears();
            if(new HashSet<>(clears).size()!=clears.size()) invalid();
            for(var value:values.entrySet()) {
                if(!DESTINATIONS.contains(value.getKey()) || Set.of("ignore","legacyStages").contains(value.getKey()) || value.getValue()==null) invalid();
                if(ImportCanonicalizer.bytes(value.getValue())>limits.cellBytes()) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"IMPORT_SIZE_LIMIT","A corrected scalar exceeds its size limit.");
            }
            for(String clear:clears) if(clear==null || !OPTIONAL.contains(clear) || values.containsKey(clear)) invalid();
            normalized.add(new RowOverride(row.rowNumber(),row.action(),row.targetId(),new TreeMap<>(values),List.copyOf(clears),row.stageMode(),row.acknowledgeLegacy()));
        }
        normalized.sort(Comparator.comparingInt(RowOverride::rowNumber));
        return new Options(1,new TreeMap<>(input.mapping()),input.dateFormat(),List.copyOf(normalized),input.supersedesPreviewId());
    }

    public List<PreviewRow> project(CsvImportParser.ParsedCsv csv,Options input,List<ApplicationView> applications) {
        Options options=validateOptions(input,csv.headers().size(),csv.records().size());
        Map<UUID,ApplicationView> owned=new HashMap<>();
        applications.stream().filter(a->!a.deleted()).forEach(a->owned.put(a.id(),a));
        Map<Integer,RowOverride> overrides=new HashMap<>(); options.rows().forEach(r->overrides.put(r.rowNumber(),r));
        List<PreviewRow> result=new ArrayList<>();
        for(int index=0;index<csv.records().size();index++) {
            ImportUploadPolicy.checkCurrentDeadline();
            RowOverride override=overrides.getOrDefault(index+1,new RowOverride(index+1,null,null,Map.of(),List.of(),null,false));
            Map<String,String> source=new HashMap<>();
            for(var mapping:options.mapping().entrySet()) if(!mapping.getValue().equals("ignore")) source.put(mapping.getValue(),csv.records().get(index).get(Integer.parseInt(mapping.getKey())));
            if(!source.getOrDefault("stages","").isEmpty()) checkStageWhitelist(source.get("stages"));
            source.putAll(override.values());
            // Security-sensitive stage metadata is forbidden even when the row is excluded or stages are preserved.
            if(!source.getOrDefault("stages","").isEmpty()) checkStageWhitelist(source.get("stages"));
            result.add(projectRow(index+1,csv.records().get(index),source,override,options,owned));
        }
        Map<UUID,Integer> targetCounts=new HashMap<>();
        result.stream().filter(r->!r.action().equals("Skip") && r.targetId()!=null).forEach(r->targetCounts.merge(r.targetId(),1,Integer::sum));
        return result.stream().map(row->{
            if(!row.action().equals("Skip") && row.targetId()!=null && targetCounts.get(row.targetId())>1) {
                List<Issue> issues=new ArrayList<>(row.issues()); issues.add(issue("IMPORT_DUPLICATE_TARGET","applicationId","Multiple included rows target the same application."));
                return new PreviewRow(row.rowNumber(),row.cells(),row.action(),row.targetId(),row.targetVersion(),row.targetSummary(),row.candidates(),row.moreCandidates(),row.intents(),row.before(),row.after(),row.differences(),List.copyOf(issues),row.warnings());
            }
            return row;
        }).toList();
    }

    private PreviewRow projectRow(int number,List<String> cells,Map<String,String> source,RowOverride override,Options options,Map<UUID,ApplicationView> owned) {
        List<Issue> issues=new ArrayList<>(); List<String> warnings=new ArrayList<>();
        String idText=source.getOrDefault("applicationId",""); UUID selected=override.targetId(); boolean controlled=!idText.isEmpty();
        if(controlled) {
            UUID parsed=null; try { parsed=UUID.fromString(idText); if(!parsed.toString().equalsIgnoreCase(idText)) parsed=null; } catch(IllegalArgumentException ignored) {}
            if(parsed==null || !owned.containsKey(parsed)) issues.add(issue("IMPORT_TARGET_UNAVAILABLE","applicationId","The selected application is unavailable."));
            if(selected!=null && !selected.equals(parsed)) issues.add(issue("IMPORT_TARGET_CONFLICT","applicationId","The application ID and selected target conflict."));
            selected=parsed;
        }
        String action=override.action()!=null?override.action():(controlled?"Update":"Add");
        if(action.equals("Add") && controlled) issues.add(issue("IMPORT_TARGET_CONFLICT","applicationId","Remove the application ID before choosing Add."));
        ApplicationView target=owned.get(selected);
        if(action.equals("Update") && target==null && issues.stream().noneMatch(i->i.code().equals("IMPORT_TARGET_UNAVAILABLE"))) issues.add(issue("IMPORT_TARGET_UNAVAILABLE","applicationId","The selected application is unavailable."));
        if(action.equals("Add")) { target=null; selected=null; }
        List<ApplicationView> matches=owned.values().stream().filter(a->norm(a.company()).equals(norm(source.getOrDefault("company",""))) && norm(a.role()).equals(norm(source.getOrDefault("role",""))))
                .sorted(Comparator.comparing(a->a.id().toString())).toList();
        if(action.equals("Add") && !matches.isEmpty()) warnings.add("Possible existing applications match this company and role. Add will create a new application.");
        List<Candidate> candidates=matches.stream().limit(5).map(ImportSemantics::candidate).toList();
        ApplicationRequest before=target==null?null:ApplicationRules.fromView(target);
        Map<String,Intent> intents=new TreeMap<>();
        for(String field:EDITABLE) {
            String raw=source.get(field);
            boolean explicitlySet=override.values().containsKey(field);
            Intent intent;
            if(override.clears().contains(field)) intent=new Intent("CLEAR",null);
            else if(field.equals("stages") && target!=null && !"REPLACE".equals(override.stageMode())) intent=new Intent("KEEP",null);
            else if(raw==null || (raw.isEmpty() && !explicitlySet)) intent=new Intent("KEEP",null);
            else intent=new Intent("SET",raw);
            intents.put(field,intent);
        }
        ApplicationRequest after=null;
        if(!action.equals("Skip")) {
            if(!source.getOrDefault("legacyStages","").isEmpty()) {
                boolean replacing=!source.getOrDefault("stages","").isEmpty() && (target==null || "REPLACE".equals(override.stageMode()));
                if(!override.acknowledgeLegacy() && !replacing) issues.add(issue("IMPORT_LEGACY_ACK_REQUIRED","legacyStages","Acknowledge excluding legacy stage text or provide structured replacements."));
                else warnings.add(target==null?"Legacy stage text is excluded; only structured stages, if supplied, are imported.":"Legacy stage text is excluded; existing stages are preserved unless replacement is selected.");
            }
            if(!(controlled && target==null) && !(action.equals("Update") && target==null)) {
                try {
                    ApplicationRequest base=before!=null?before:new ApplicationRequest("","","",ApplicationStatus.SAVED,null,null,null,"",List.of());
                    List<StageRequest> stages=stages(intents.get("stages"),base.stages());
                    ApplicationRequest proposed=new ApplicationRequest(text("company",intents,base.company()),text("role",intents,base.role()),text("location",intents,base.location()),
                            status(intents.get("status"),base.status()),date("appliedDate",intents,base.appliedDate(),options.dateFormat()),date("deadline",intents,base.deadline(),options.dateFormat()),
                            date("followUpDate",intents,base.followUpDate(),options.dateFormat()),text("notes",intents,base.notes()),stages);
                    after=rules.normalizeAndValidate(proposed);
                    if(before!=null && stageContent(before.stages()).equals(stageContent(after.stages())) && !before.stages().equals(after.stages())) {
                        after=new ApplicationRequest(after.company(),after.role(),after.location(),after.status(),after.appliedDate(),after.deadline(),after.followUpDate(),after.notes(),before.stages());
                    }
                    if(before!=null && !before.stages().equals(after.stages())) warnings.add("Replacing interview stages creates new stage identities.");
                    if(action.equals("Update") && after.equals(before)) action="Unchanged";
                } catch(FieldError e) { issues.add(issue("IMPORT_INVALID_VALUE",e.field,"The value is invalid for this field.")); }
                catch(ApplicationRules.ValidationFailure e) { issues.add(issue("IMPORT_VALIDATION_FAILED",e.field(),e.getMessage())); }
                catch(ApiException | jakarta.validation.ConstraintViolationException | IllegalArgumentException e) { issues.add(issue("IMPORT_VALIDATION_FAILED","record","The complete application does not satisfy application validation rules.")); }
            }
        } else { issues.clear(); warnings.add("Excluded by your row selection; this row writes no application."); }
        List<Difference> differences=new ArrayList<>();
        if(after!=null) {
            Map<String,String> old=before==null?Map.of():flatten(before); Map<String,String> next=flatten(after);
            for(String field:EDITABLE) if(!Objects.equals(old.get(field),next.get(field))) differences.add(new Difference(field,old.getOrDefault(field,""),next.get(field)));
        }
        return new PreviewRow(number,List.copyOf(cells),action,selected,target==null?null:target.version(),target==null?null:candidate(target),candidates,matches.size()>5,
                Collections.unmodifiableMap(intents),before,after,List.copyOf(differences),List.copyOf(issues),List.copyOf(warnings));
    }

    private String text(String field,Map<String,Intent> intents,String previous) {
        Intent i=intents.get(field); return i.intent().equals("KEEP")?previous:i.intent().equals("CLEAR")?"":i.value();
    }
    private ApplicationStatus status(Intent intent,ApplicationStatus previous) {
        if(intent.intent().equals("KEEP")) return previous;
        try { return ApplicationStatus.parse(intent.value()); } catch(IllegalArgumentException e) { throw new FieldError("status"); }
    }
    private LocalDate date(String field,Map<String,Intent> intents,LocalDate previous,String policy) {
        Intent i=intents.get(field); if(i.intent().equals("KEEP")) return previous; if(i.intent().equals("CLEAR")) return null;
        String pattern=switch(policy) { case "MDY"->"MM/dd/uuuu"; case "DMY"->"dd/MM/uuuu"; default->"uuuu-MM-dd"; };
        try { return LocalDate.parse(i.value(),DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT)); }
        catch(DateTimeException e) { throw new FieldError(field); }
    }
    private List<StageRequest> stages(Intent intent,List<StageRequest> previous) {
        if(intent.intent().equals("KEEP")) return previous;
        if(intent.value().isEmpty()) return previous;
        try {
            JsonNode tree=json.readTree(intent.value()); if(!tree.isArray() || tree.size()>30) throw new FieldError("stages");
            List<StageRequest> result=new ArrayList<>();
            for(JsonNode node:tree) {
                if(!node.isObject() || !node.path("name").isTextual() || !node.path("state").isTextual()) throw new FieldError("stages");
                JsonNode d=node.get("date");
                if(d!=null && !d.isNull() && !d.isTextual()) throw new FieldError("stages");
                LocalDate date=d==null || d.isNull()?null:LocalDate.parse(d.asText(),DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT));
                result.add(new StageRequest(null,node.get("name").asText(),date,StageState.parse(node.get("state").asText())));
            }
            return List.copyOf(result);
        } catch(java.io.IOException | DateTimeException | IllegalArgumentException e) { throw new FieldError("stages"); }
    }
    private void checkStageWhitelist(String value) {
        try {
            JsonNode tree=json.readTree(value);
            if(tree.isArray()) for(JsonNode node:tree) if(node.isObject()) {
                var fields=node.fieldNames();
                while(fields.hasNext()) if(!Set.of("name","date","state").contains(fields.next()))
                    throw new ApiException(HttpStatus.BAD_REQUEST,"IMPORT_RESERVED_METADATA","Uploaded stage metadata is not permitted.");
            }
        } catch(java.io.IOException ignored) { /* Ordinary syntax issues can be excluded at row level. */ }
    }
    private Map<String,String> flatten(ApplicationRequest a) {
        Map<String,String> result=new LinkedHashMap<>(); result.put("company",a.company());result.put("role",a.role());result.put("location",a.location());result.put("status",a.status().label());
        result.put("appliedDate",Objects.toString(a.appliedDate(),""));result.put("deadline",Objects.toString(a.deadline(),""));result.put("followUpDate",Objects.toString(a.followUpDate(),""));result.put("notes",a.notes());
        try { result.put("stages",json.writeValueAsString(stageContent(a.stages()))); } catch(java.io.IOException e) { throw new IllegalStateException(e); }
        return result;
    }
    private List<List<String>> stageContent(List<StageRequest> stages) { return stages.stream().map(s->List.of(s.name(),Objects.toString(s.date(),""),s.state().label())).toList(); }
    private static String norm(String value) { return value.trim().toLowerCase(Locale.ROOT); }
    private static Candidate candidate(ApplicationView a) { return new Candidate(a.id(),a.company(),a.role(),a.location()); }
    private static Issue issue(String code,String field,String message) { return new Issue(code,field,message); }
    private static void invalid() { throw new ApiException(HttpStatus.BAD_REQUEST,"IMPORT_INVALID_OPTIONS","Import options are invalid."); }
    private static class FieldError extends RuntimeException { final String field; FieldError(String field) { this.field=field; } }
}
