package com.offline.translator.data.models

import com.offline.translator.domain.models.CatalogOutcome
import com.offline.translator.domain.models.ModelFailure
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class CatalogStore(private val file: File, private val reader: SignedCatalogReader) {
    fun read(): CatalogOutcome? = if (file.exists()) reader.read(file.readBytes()) else null

    fun refresh(bytes: ByteArray): CatalogOutcome {
        if (file.exists() && file.readBytes().contentEquals(bytes)) return reader.read(bytes)
        return replace(bytes)
    }

    fun replace(bytes: ByteArray): CatalogOutcome {
        val result = reader.read(bytes)
        if (result !is CatalogOutcome.Verified) return result
        val current = if (file.exists()) reader.read(file.readBytes(), acceptExpired = true) as? CatalogOutcome.Verified else null
        if (current != null && result.catalog.revision <= current.catalog.revision) {
            return CatalogOutcome.Rejected(ModelFailure.CatalogRollback)
        }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            temp.outputStream().use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
        return result
    }
}
