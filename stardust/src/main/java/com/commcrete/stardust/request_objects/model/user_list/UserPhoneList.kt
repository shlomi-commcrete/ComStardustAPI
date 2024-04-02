package com.commcrete.stardust.request_objects.model.user_list

data class UserPhoneList(
    val users: List<User>
)

fun UserPhoneList.getPhoneNumbers(): MutableMap<String, String> {
    val phoneNumbers = mutableMapOf<String, String>()
    for(user in this.users ) {
        phoneNumbers[user.phone] = user.displayName
    }
    return phoneNumbers
}

fun List<User>.getPhoneNumbers(): MutableMap<String, String> {
    val phoneNumbers = mutableMapOf<String, String>()
    for(user in this ) {
        phoneNumbers[user.phone] = user.displayName
    }
    return phoneNumbers
}
