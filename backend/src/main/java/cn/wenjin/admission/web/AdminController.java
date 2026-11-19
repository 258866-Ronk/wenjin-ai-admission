package cn.wenjin.admission.web;

import cn.wenjin.admission.common.Ids;
import cn.wenjin.admission.common.Jsons;
import cn.wenjin.admission.data.AdmissionDataImporter;
import cn.wenjin.admission.data.AdmissionDataImporter.ImportOptions;
import cn.wenjin.admission.data.AdmissionDataImporter.ImportResult;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Instant;

@RestController @RequestMapping("/api/v1/admin")
public class AdminController {
    private final AdmissionDataImporter importer; private final JdbcClient jdbc;
    public AdminController(AdmissionDataImporter importer,JdbcClient jdbc){this.importer=importer;this.jdbc=jdbc;}
    @PostMapping("/imports") Object importData(@RequestBody ImportRequest request){
        Path input=Path.of(request.path()).toAbsolutePath().normalize();
        if(!Files.exists(input))throw new IllegalArgumentException("导入路径不存在");
        List<Path> files=new ArrayList<>();
        try{
            if(Files.isDirectory(input)){try(var stream=Files.walk(input)){files.addAll(stream.filter(Files::isRegularFile).filter(p->p.getFileName().toString().toLowerCase().endsWith(".json")).limit(5000).toList());}}
            else if(input.getFileName().toString().toLowerCase().endsWith(".json"))files.add(input);
            else throw new IllegalArgumentException("只支持 JSON 文件或目录");
        }catch(Exception e){throw new IllegalArgumentException("无法扫描导入路径");}
        String dataset=request.datasetVersion()==null||request.datasetVersion().isBlank()?"manual-import":request.datasetVersion();
        String batchId=Ids.uuid();Instant now=Instant.now();
        jdbc.sql("insert into import_batches(id,dataset_version,source_path,mode,dry_run,status,scanned_files,created_at) values (:id,:dataset,:path,'UPSERT',:dryRun,'RUNNING',:files,:now)")
                .param("id",batchId).param("dataset",dataset).param("path",input.toString()).param("dryRun",request.dryRun()).param("files",files.size()).param("now",java.sql.Timestamp.from(now)).update();
        List<ImportResult> results=new ArrayList<>();int successfulFiles=0,failedFiles=0,read=0,inserted=0,updated=0,skipped=0,duplicates=0;
        for(Path file:files){
            try{
                ImportResult result=importer.importResource(new FileSystemResource(file),new ImportOptions(dataset,request.dryRun(),request.provinces(),request.years(),request.dataTypes()));
                results.add(result);read+=result.readRecords();inserted+=result.insertedRecords();updated+=result.updatedRecords();skipped+=result.skippedRecords();duplicates+=result.duplicateRecords();
                if(result.failures().isEmpty())successfulFiles++;else failedFiles++;
                for(var failure:result.failures())saveFailure(batchId,file,failure.recordIndex(),failure.reason(),failure.rawExcerpt());
            }catch(Exception exception){
                failedFiles++;saveFailure(batchId,file,null,safeMessage(exception),null);
            }
        }
        String status=failedFiles==0?"COMPLETED":successfulFiles==0?"FAILED":"PARTIAL";
        Map<String,Object> statistics=Map.of("requestedProvinces",request.provinces(),"requestedYears",request.years(),"requestedDataTypes",request.dataTypes(),"resultFiles",results.size());
        jdbc.sql("update import_batches set status=:status,successful_files=:successful,failed_files=:failed,read_records=:read,inserted_records=:inserted,updated_records=:updated,skipped_records=:skipped,duplicate_records=:duplicates,statistics_json=:statistics,completed_at=:completed where id=:id")
                .param("status",status).param("successful",successfulFiles).param("failed",failedFiles).param("read",read).param("inserted",inserted).param("updated",updated).param("skipped",skipped).param("duplicates",duplicates).param("statistics",Jsons.write(statistics)).param("completed",java.sql.Timestamp.from(Instant.now())).param("id",batchId).update();
        return Map.of("batchId",batchId,"status",status,"scannedFiles",files.size(),"results",results);
    }
    @GetMapping("/imports")Object imports(){return jdbc.sql("select id,dataset_version,source_path,mode,dry_run,status,scanned_files,read_records,inserted_records,updated_records,duplicate_records,created_at from import_batches order by created_at desc fetch first 100 rows only").query((rs,n)->new ImportBatchView(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getBoolean(5),rs.getString(6),rs.getInt(7),rs.getInt(8),rs.getInt(9),rs.getInt(10),rs.getInt(11),rs.getTimestamp(12).toInstant())).list();}
    @GetMapping("/imports/{id}")Object importBatch(@PathVariable String id){var batch=jdbc.sql("select id,dataset_version,source_path,mode,dry_run,status,scanned_files,read_records,inserted_records,updated_records,duplicate_records,created_at from import_batches where id=:id").param("id",id).query((rs,n)->new ImportBatchView(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getBoolean(5),rs.getString(6),rs.getInt(7),rs.getInt(8),rs.getInt(9),rs.getInt(10),rs.getInt(11),rs.getTimestamp(12).toInstant())).optional().orElseThrow(()->new IllegalArgumentException("导入批次不存在"));var failures=jdbc.sql("select source_file,record_index,reason,raw_excerpt,created_at from import_failures where batch_id=:id order by created_at").param("id",id).query((rs,n)->Map.of("sourceFile",rs.getString(1),"recordIndex",rs.getObject(2)==null?-1:rs.getInt(2),"reason",rs.getString(3),"rawExcerpt",rs.getString(4)==null?"":rs.getString(4),"createdAt",rs.getTimestamp(5).toInstant())).list();return Map.of("batch",batch,"failures",failures);}
    @GetMapping("/data-quality")Object quality(){long records=jdbc.sql("select count(*) from admission_records").query(Long.class).single();long sources=jdbc.sql("select count(*) from data_sources").query(Long.class).single();long missingRank=jdbc.sql("select count(*) from admission_records where min_rank_end is null").query(Long.class).single();long special=jdbc.sql("select count(*) from admission_records where formal_score_scope like '%special-path%'").query(Long.class).single();return Map.of("records",records,"sources",sources,"missingRank",missingRank,"specialPathIsolated",special);}
    @GetMapping("/diagnostics")Object diagnostics(){return Map.of("runs",jdbc.sql("select count(*) from advisor_runs").query(Long.class).single(),"completedRuns",jdbc.sql("select count(*) from advisor_runs where status in ('COMPLETED','DEGRADED')").query(Long.class).single(),"reports",jdbc.sql("select count(*) from reports").query(Long.class).single(),"aiMode","configured-by-environment");}
    record ImportRequest(String path,String datasetVersion,boolean dryRun,List<String> provinces,List<Integer> years,List<String> dataTypes){public ImportRequest{provinces=provinces==null?List.of():provinces;years=years==null?List.of():years;dataTypes=dataTypes==null?List.of():dataTypes;}}
    record ImportBatchView(String id,String datasetVersion,String sourcePath,String mode,boolean dryRun,String status,int scannedFiles,int readRecords,int insertedRecords,int updatedRecords,int duplicateRecords,Instant createdAt){}
    private void saveFailure(String batchId,Path file,Integer index,String reason,String excerpt){jdbc.sql("insert into import_failures(id,batch_id,source_file,record_index,reason,raw_excerpt,created_at) values (:id,:batchId,:file,:recordIndex,:reason,:excerpt,:now)").param("id",Ids.uuid()).param("batchId",batchId).param("file",file.toString()).param("recordIndex",index).param("reason",reason).param("excerpt",excerpt).param("now",java.sql.Timestamp.from(Instant.now())).update();}
    private static String safeMessage(Exception exception){String message=exception.getMessage();return message==null?"导入失败":message.substring(0,Math.min(message.length(),1900));}
}
