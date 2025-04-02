package com.airobot.core.utils.primitive

import io.ktor.util.reflect.TypeInfo
import io.ktor.util.reflect.platformType
import io.ktor.util.reflect.typeInfo
import kotlin.reflect.KClass
import kotlin.reflect.KClassifier
import kotlin.reflect.KType
import kotlin.reflect.KTypeProjection


//expect  inline fun<reified T: Any> KClass<T>.createType(): KType


inline fun <reified T : Any> KClass<T>.createType(): KType {
    val typeInfo = typeInfo<T>()
    val parentType = typeInfo.kotlinType!!
//    val modelKTypeProjection = parentType.arguments.first()
//    val innerType: KType? = modelKTypeProjection.type
////     when (type) {
////        3 ->
////        1 -> (modelKTypeProjection.type)!!.arguments.first().type
////        else -> null
////    }
//    val kClazz: KClass<*> = innerType?.classifier as KClass<*>
//    val typeImpl = KTypeImpl(
//        modelKTypeProjection,
//        clazz = kClazz
//    )
//    val realTypeInfo = TypeInfo(
//        typeImpl.classifier as KClass<*>,
//        typeImpl.platformType,
//        typeImpl
//    )
    return parentType
}



//expect internal  class KTypeImpl(
////    expect val kClass: KClass<*>,
////    expect override val arguments: List<KTypeProjection>,
////    expect override val isMarkedNullable: Boolean
//) : KType {
//    override val classifier: KClassifier
////        get() = kClass
//}