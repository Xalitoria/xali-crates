// What other Paper plugins compile against to interact with XaliCrates. The server provides it at run time from
// XaliCrates' jar, so a plugin that uses it lists XaliCrates as a dependency and does not bundle this.

plugins {
    `maven-publish`
}

dependencies {
    compileOnly(libs.paper.api)
    testImplementation(libs.paper.api)
}

tasks.test {
    useJUnitPlatform()
}
