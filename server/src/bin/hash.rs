fn main() {
    let hash = bcrypt::hash("admin123", bcrypt::DEFAULT_COST).unwrap();
    println!("{}", hash);
}
