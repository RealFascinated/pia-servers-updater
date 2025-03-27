package cc.fascinated.piaservers.utils;

public class Strings {
    /**
     * Formats a region name to a more readable format.
     * 
     * @param region The region name to format.
     * @return The formatted region name.
     */
    public static String formatRegion(String region) {
        String[] words = region.replaceAll("_", " ").split(" ");
        StringBuilder result = new StringBuilder();
        
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.length() <= 2) {
                result.append(word.toUpperCase());
            } else {
                result.append(Character.toUpperCase(word.charAt(0)))
                      .append(word.substring(1).toLowerCase());
            }
            if (i < words.length - 1) {
                result.append(" ");
            }
        }
        
        return result.toString();
    }
}
