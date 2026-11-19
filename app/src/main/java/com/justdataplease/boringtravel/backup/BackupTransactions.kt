package com.justdataplease.boringtravel.backup

/**
 * A restore's database work as one step: read the phone, keep it for Undo, write the backup. The phone is read after
 * the transaction is acquired, not before, so a note saved while the backup downloaded is in the copy kept for Undo.
 * When [keep] fails nothing is written: a restore never changes a phone it couldn't keep a copy of.
 */
internal object BackupTransactions {
    suspend fun replace(
        incoming: Snapshot,
        shared: Set<String>,
        transaction: suspend (suspend () -> MergePlan) -> MergePlan,
        read: suspend () -> Snapshot,
        keep: suspend (Snapshot) -> Unit,
        write: suspend (MergePlan) -> Unit,
    ): MergePlan = transaction {
        val local = read()
        val plan = BackupMerge.replace(local, incoming, shared)
        if (!plan.isEmpty) {
            keep(local)
            write(plan)
        }
        plan
    }
}
