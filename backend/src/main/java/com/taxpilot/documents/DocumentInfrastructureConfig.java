package com.taxpilot.documents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.taxpilot.config.TaxpilotProperties;
import com.taxpilot.documents.scan.ClamAvScanner;
import com.taxpilot.documents.scan.MalwareScanner;
import com.taxpilot.documents.storage.LocalObjectStorage;
import com.taxpilot.documents.storage.ObjectStorage;
import com.taxpilot.documents.storage.S3ObjectStorage;

@Configuration
public class DocumentInfrastructureConfig {

    private static final Logger log = LoggerFactory.getLogger(DocumentInfrastructureConfig.class);

    @Bean
    ObjectStorage objectStorage(TaxpilotProperties props) {
        TaxpilotProperties.Storage cfg = props.storage();
        if ("s3".equalsIgnoreCase(cfg.type())) {
            log.info("Document storage: S3-compatible bucket '{}'", cfg.s3().bucket());
            return new S3ObjectStorage(cfg.s3());
        }
        log.info("Document storage: local filesystem (encrypted at application level)");
        return new LocalObjectStorage(cfg.localPath());
    }

    @Bean
    MalwareScanner malwareScanner(TaxpilotProperties props) {
        TaxpilotProperties.Scan scan = props.scan();
        if (scan != null && scan.enabled()) {
            log.info("Malware scanning: ClamAV at {}:{}", scan.host(), scan.port());
            return new ClamAvScanner(scan.host(), scan.port(), scan.timeout());
        }
        log.warn("Malware scanning is DISABLED. Uploads are stored with scan_status=NOT_SCANNED. "
                + "Enable taxpilot.scan.enabled for staging and production.");
        return data -> new MalwareScanner.Result(MalwareScanner.Verdict.NOT_SCANNED, null);
    }
}
