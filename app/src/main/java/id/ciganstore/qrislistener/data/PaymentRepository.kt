package id.ciganstore.qrislistener.data

interface PaymentRepository {
    fun insertIfNew(event: PaymentEvent): Boolean
    fun listNewest(limit: Int): List<PaymentEvent>
    fun clear()
}

