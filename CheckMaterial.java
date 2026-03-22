public class CheckMaterial {
  public static void main(String[] args) {
    for (String name : new String[]{"OAK_SHELF", "CHISELED_BOOKSHELF", "BOOKSHELF"}) {
      try {
        System.out.println(name + "=" + org.bukkit.Material.valueOf(name));
      } catch (Exception e) {
        System.out.println(name + "=MISSING");
      }
    }
  }
}
