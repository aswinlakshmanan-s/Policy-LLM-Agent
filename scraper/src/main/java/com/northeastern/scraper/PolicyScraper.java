package com.northeastern.scraper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PolicyScraper {
    public static void main(String[] args) {
        File dir = new File("data/policies");
        if (!dir.exists()) dir.mkdirs();

        List<String> allPolicies = getAllPolicyIds();
        for (String policyId : allPolicies) {
            String url = "https://policies.northeastern.edu/policy" + policyId + "/";
            System.out.println("Fetching policy " + policyId + "...");

            try {
                Document doc = Jsoup.connect(url).get();
                Element titleEl = doc.selectFirst("h1.et_pb_module_header");
                Element contentEl = doc.selectFirst("div.et_pb_section.et_pb_section_1");
                if (titleEl == null || contentEl == null) {
                    System.out.println("⚠️ Skipping missing policy: " + policyId);
                    continue;
                }
                StringBuilder content = new StringBuilder();
                content.append("Policy ").append(policyId).append(": ").append(titleEl.text()).append("\n\n");
                Elements bodyParts = contentEl.select("h2, h3, p, li");
                for (Element el : bodyParts) content.append(el.text()).append("\n");

                try (FileWriter writer = new FileWriter(new File(dir, "policy_" + policyId + ".txt"))) {
                    writer.write(content.toString());
                }
                System.out.println("✅ Saved policy_" + policyId + ".txt");

            } catch (IOException e) {
                System.err.println("❌ Error fetching " + policyId + ": " + e.getMessage());
            }
        }
    }

    private static List<String> getAllPolicyIds() {
        List<String> ids = new ArrayList<>();
        addRange(ids, 101, 126);
        addRange(ids, 200, 208);
        addRange(ids, 300, 312);
        addRange(ids, 400, 429);
        addRange(ids, 500, 513);
        addRange(ids, 600, 618);
        addRange(ids, 800, 808);

        ids.add("700"); ids.add("701"); ids.add("702"); ids.add("703"); ids.add("704");
        ids.add("705"); ids.add("706"); ids.add("708");
        String[] canPolicies = {"107-CAN", "206-CAN", "207-CAN", "402-CAN", "403-CAN", "405-CAN",
                "406-CAN", "408-CAN", "418-CAN", "421-CAN", "424-CAN", "603-CAN", "707-CAN"};
        for (String p : canPolicies) ids.add(p);
        return ids;
    }
    private static void addRange(List<String> ids, int start, int end) {
        for (int i = start; i <= end; i++) ids.add(String.valueOf(i));
    }
}
