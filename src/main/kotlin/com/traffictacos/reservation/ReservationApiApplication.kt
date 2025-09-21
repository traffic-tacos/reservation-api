package com.traffictacos.reservation

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ReservationApiApplication

fun main(args: Array<String>) {
	runApplication<ReservationApiApplication>(*args)
}
