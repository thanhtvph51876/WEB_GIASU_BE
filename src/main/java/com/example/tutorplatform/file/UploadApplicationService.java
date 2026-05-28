package com.example.tutorplatform.file;

import com.example.tutorplatform.db.DbService;
import com.example.tutorplatform.file.FileStorageService.StoredFile;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class UploadApplicationService {
  private final DbService db;
  private final FileStorageService fileStorage;

  public UploadApplicationService(DbService db, FileStorageService fileStorage) {
    this.db = db;
    this.fileStorage = fileStorage;
  }

  public Map<String, Object> upload(MultipartFile file, String visibility, String purpose) throws IOException {
    StoredFile stored = fileStorage.store(file, db.currentUserIdOrThrow(), visibility, purpose);
    if ("private".equals(stored.visibility())) {
      db.auditCurrent("file.upload_private", "uploadedFile", stored.id(), "Người dùng tải lên file riêng tư.");
    }
    return fileStorage.response(stored);
  }
}
