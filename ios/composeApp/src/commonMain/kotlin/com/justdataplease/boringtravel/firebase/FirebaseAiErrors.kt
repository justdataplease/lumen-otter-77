package com.justdataplease.boringtravel.firebase

/*
 * Firebase AI Logic's failures under the names its Android SDK throws (firebase-ai 17.2.0, com.google.firebase.ai.type):
 * the AI planner's PlannerError sorts a failure by these names and the words of its message, so the built-in planner
 * on iPhone ([com.justdataplease.boringtravel.ai.GeminiPlanner]) throws the same ones for the same answers.
 */

/** Something wrong on Google's side, or an answer the planner couldn't use; [message] is Google's own. */
open class FirebaseAIException(message: String) : Exception(message)

class ServerException(message: String) : FirebaseAIException(message)

class InvalidAPIKeyException(message: String) : FirebaseAIException(message)

class QuotaExceededException(message: String) : FirebaseAIException(message)

class PromptBlockedException(message: String) : FirebaseAIException(message)

class ResponseStoppedException(message: String) : FirebaseAIException(message)

class ServiceDisabledException(message: String) : FirebaseAIException(message)

class UnsupportedUserLocationException : FirebaseAIException("User location is not supported for the API use.")
