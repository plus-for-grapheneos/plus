// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * +PLUS 12: every model GrapheneOS supports (Pixel 6 and later). The AVB key hashes are the verified boot key hashes
 * GrapheneOS publishes on grapheneos.org/install/cli; tools/collect-pins.py took both keys from each model's official
 * 2026102100 OTA and avbroot verified each whole OTA against them (on the build computer, 2026-10-23). The certificate hashes are
 * of the pinned PEM files, so a swapped or edited key file fails here.
 */
class AllModelsPinsTest {
    private data class Model(val device: String, val name: String, val avbKeySha256: String, val otaCertSha256: String)

    private val models = listOf(
        Model("akita", "Pixel 8a", "096b8bd6d44527a24ac1564b308839f67e78202185cbff9cfdcb10e63250bc5e", "b419e7ee5580c0d7229163340f7bac1be0cff189147dd05532bb776292b29dfa"),
        Model("blazer", "Pixel 10 Pro", "4e8ee8f717754052198ca6d2d3aaa232e2461b4293c0d6f297e519cc778de093", "02a1bce4cfe1bf6494123eadc1d10888a09ad9a7ddfab5a5c81202c8af15ab07"),
        Model("bluejay", "Pixel 6a", "08c860350a9600692d10c8512f7b8e80707757468e8fbfeea2a870c0a83d6031", "6dab5e92515643e99a586d7a3c847c8bafb2e52cf434d1059011672cb614e3f8"),
        Model("caiman", "Pixel 9 Pro", "f729cab861da1b83fdfab402fc9480758f2ae78ee0b61c1f2137dd1ab7076e86", "3cc779dc5570513923d0584a546afb4b6422d2ac57054f80609a2c89ced1736c"),
        Model("cheetah", "Pixel 7 Pro", "bc1c0dd95664604382bb888412026422742eb333071ea0b2d19036217d49182f", "2f4a059ff3085955083d8b8934d8dd0bd0a30e292d1a1817cb826a432a3767ca"),
        Model("comet", "Pixel 9 Pro Fold", "af4d2c6e62be0fec54f0271b9776ff061dd8392d9f51cf6ab1551d346679e24c", "036b87489384578786f99b3dd2b3d0098d2be2defef7d94ed8a1ee63d11fcc57"),
        Model("felix", "Pixel Fold", "ee0c9dfef6f55a878538b0dbf7e78e3bc3f1a13c8c44839b095fe26dd5fe2842", "c60adbf8f8a93fe89d29951f39bb8366eb45decc5f97dae6bad325e1ba859950"),
        Model("frankel", "Pixel 10", "3f7415ea26f5df5b14ea6d153256071a7a1af9ce7b0970b7311cc463c7ea02c7", "f48ad1f626b21dc9781b5c1c6f2c00b1e5b5d0aace2ba80772efdccd677de36d"),
        Model("husky", "Pixel 8 Pro", "896db2d09d84e1d6bb747002b8a114950b946e5825772a9d48ba7eb01d118c1c", "1ab8eeccd9357a5526c51dda37a5647b7c95276befb0684beb69bff691ee4bdd"),
        Model("komodo", "Pixel 9 Pro XL", "55d3c2323db91bb91f20d38d015e85112d038f6b6b5738fe352c1a80dba57023", "327c7bf51a27a00eca63d6ee90d9bc53a2d29d498cf6c078bf34d6e151fdcf54"),
        Model("lynx", "Pixel 7a", "508d75dea10c5cbc3e7632260fc0b59f6055a8a49dd84e693b6d8899edbb01e4", "b42ea13d79d98d130a5c5c846b97c10f1da55dea888cb2b8fe3dd35c20b40a43"),
        Model("mustang", "Pixel 10 Pro XL", "141d7fc32af7958a416f2661b37cf6f27bfb376fb5ce616aeaa27a82c7a04f74", "3c4ff3520a4b6efda5bba85aa5271bc1ef2fa43c633241b574ce208e8d8d7741"),
        Model("oriole", "Pixel 6", "f0a890375d1405e62ebfd87e8d3f475f948ef031bbf9ddd516d5f600a23677e8", "2d0a5c4f9885f9e866bda7481c122a7ae1db198cc95786d04f2edce54272eaa4"),
        Model("panther", "Pixel 7", "3efe5392be3ac38afb894d13de639e521675e62571a8a9b3ef9fc8c44fd17fa1", "eef7c1a9615ad8dd8f83d715b9c376f62db93f76572650510b7eed2b9f241f8e"),
        Model("rango", "Pixel 10 Pro Fold", "55a2d44103e56d5ec65496399c417987ba77730e6488fc60ba058d09fc3caee3", "e20215a1dfed63618187498cd81a55d364c83b4f87b507b81bf07ef70a33dc24"),
        Model("raven", "Pixel 6 Pro", "439b76524d94c40652ce1bf0d8243773c634d2f99ba3160d8d02aa5e29ff925c", "20058cd341432fbaccd5ac5af8ebe4681c273f92f8c5e18b3045e3c22b9155c1"),
        Model("shiba", "Pixel 8", "cd7479653aa88208f9f03034810ef9b7b0af8a9d41e2000e458ac403a2acb233", "79f0f5aacd8eb9c4e7fff18ce125a1c726b5cf444b9d4ec2b1dee53d4f54ebe9"),
        Model("stallion", "Pixel 10a", "d8f879d10419eddc9fcda6280718be763f6bf12299e1f72df3ea8ad8a8eb7f80", "90a9e86d4aa38b3b780d7be25a537a09d420f8c4e1b733db0dd13b225096ea7f"),
        Model("tangorpro", "Pixel Tablet", "94df136e6c6aa08dc26580af46f36419b5f9baf46039db076f5295b91aaff230", "2d6a4cd30d074b749f5ad78a54004364bf1dee179a2b2e9b39a8a34c10b31638"),
        Model("tegu", "Pixel 9a", "0508de44ee00bfb49ece32c418af1896391abde0f05b64f41bc9a2dfb589445b", "98c950c5f4b4f7e6985be18af83aeb36d3f6fee49852657956340b82c12e738d"),
        Model("tokay", "Pixel 9", "9e6a8f3e0d761a780179f93acd5721ba1ab7c8c537c7761073c0a754b0e932de", "937446159536f82de3fe2a04e7b94491ffc8be375106de5caa07b01217d43c8c"),
    )

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun every_grapheneos_model_is_supported() {
        assertEquals(models.map { it.device }.sorted(), Pins.SUPPORTED_DEVICES.sorted())
    }

    @Test
    fun each_models_avb_key_is_the_one_grapheneos_publishes() {
        for (m in models) {
            val pins = Pins.forDevice(m.device)
            assertNotNull(pins, m.device)
            assertEquals(m.avbKeySha256, sha256(pins!!.avbPkmd), m.device)
        }
    }

    @Test
    fun each_models_ota_certificate_is_the_pinned_one() {
        for (m in models) {
            val pem = Pins::class.java.getResourceAsStream("/pins/${m.device}/grapheneos_ota.x509.pem")!!.use { it.readBytes() }
            assertEquals(m.otaCertSha256, sha256(pem), m.device)
            assertEquals("CN=GrapheneOS", Pins.forDevice(m.device)!!.otaCert.subjectX500Principal.name, m.device)
        }
    }

    @Test
    fun each_model_has_its_name() {
        for (m in models) assertEquals(m.name, Pins.modelName(m.device), m.device)
        assertEquals("unknownphone", Pins.modelName("unknownphone"))
    }
}
