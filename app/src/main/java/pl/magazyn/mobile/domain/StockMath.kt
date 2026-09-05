package pl.magazyn.mobile.domain

object StockMath {
    fun correctionDelta(current: Double, counted: Double): Double = counted - current
    fun afterIssue(current: Double, issued: Double): Double = current - issued
}
