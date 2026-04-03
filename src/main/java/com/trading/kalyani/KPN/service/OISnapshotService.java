package com.trading.kalyani.KPN.service;

import com.trading.kalyani.KPN.entity.OISnapshotEntity;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

public interface OISnapshotService {

    boolean processOISnapshot(MultipartFile file);

    int deleteOISnapshotByInstrumentTokens(List<Long> instrumentTokenList);

    int loadOISnapshot(List<OISnapshotEntity> oiSnapshotEntityList);

}
