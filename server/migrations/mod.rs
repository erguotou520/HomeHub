use sea_orm_migration::prelude::*;

pub struct Migrator;

#[async_trait::async_trait]
impl MigratorTrait for Migrator {
    fn migrations() -> Vec<Box<dyn MigrationTrait>> {
        vec![
            Box::new(m20241229_000001_create_users_table::Migration),
            Box::new(m20241229_000002_create_shares_table::Migration),
        ]
    }
}

mod m20241229_000001_create_users_table {
    use sea_orm_migration::prelude::*;

    #[derive(DeriveMigrationName)]
    pub struct Migration;

    #[async_trait::async_trait]
    impl MigrationTrait for Migration {
        async fn up(&self, manager: &SchemaManager) -> Result<(), DbErr> {
            manager
                .create_table(
                    Table::create()
                        .table(Users::Table)
                        .if_not_exists()
                        .col(
                            ColumnDef::new(Users::Id)
                                .integer()
                                .not_null()
                                .auto_increment()
                                .primary_key(),
                        )
                        .col(
                            ColumnDef::new(Users::Username)
                                .string_len(64)
                                .not_null()
                                .unique_key(),
                        )
                        .col(
                            ColumnDef::new(Users::PasswordHash)
                                .string_len(128)
                                .not_null(),
                        )
                        .col(
                            ColumnDef::new(Users::Role)
                                .string_len(16)
                                .not_null()
                                .default("user"),
                        )
                        .col(
                            ColumnDef::new(Users::CreatedAt)
                                .timestamp_with_time_zone()
                                .not_null(),
                        )
                        .col(
                            ColumnDef::new(Users::UpdatedAt)
                                .timestamp_with_time_zone()
                                .not_null(),
                        )
                        .to_owned(),
                )
                .await
        }

        async fn down(&self, manager: &SchemaManager) -> Result<(), DbErr> {
            manager
                .drop_table(Table::drop().table(Users::Table).to_owned())
                .await
        }
    }

    #[derive(DeriveIden)]
    enum Users {
        Table,
        Id,
        Username,
        PasswordHash,
        Role,
        CreatedAt,
        UpdatedAt,
    }
}

mod m20241229_000002_create_shares_table {
    use sea_orm_migration::prelude::*;

    #[derive(DeriveMigrationName)]
    pub struct Migration;

    #[async_trait::async_trait]
    impl MigrationTrait for Migration {
        async fn up(&self, manager: &SchemaManager) -> Result<(), DbErr> {
            manager
                .create_table(
                    Table::create()
                        .table(Shares::Table)
                        .if_not_exists()
                        .col(
                            ColumnDef::new(Shares::Id)
                                .integer()
                                .not_null()
                                .auto_increment()
                                .primary_key(),
                        )
                        .col(
                            ColumnDef::new(Shares::UserId)
                                .integer()
                                .not_null(),
                        )
                        .col(
                            ColumnDef::new(Shares::FilePath)
                                .text()
                                .not_null(),
                        )
                        .col(
                            ColumnDef::new(Shares::AppType)
                                .string_len(32)
                                .not_null(),
                        )
                        .col(
                            ColumnDef::new(Shares::Token)
                                .string_len(32)
                                .not_null()
                                .unique_key(),
                        )
                        .col(
                            ColumnDef::new(Shares::ExpiresAt)
                                .timestamp_with_time_zone()
                                .null(),
                        )
                        .col(
                            ColumnDef::new(Shares::BurnAfterRead)
                                .boolean()
                                .not_null()
                                .default(false),
                        )
                        .col(
                            ColumnDef::new(Shares::MaxViews)
                                .integer()
                                .null(),
                        )
                        .col(
                            ColumnDef::new(Shares::ViewCount)
                                .integer()
                                .not_null()
                                .default(0),
                        )
                        .col(
                            ColumnDef::new(Shares::CreatedAt)
                                .timestamp_with_time_zone()
                                .not_null(),
                        )
                        .foreign_key(
                            ForeignKey::create()
                                .name("fk_shares_user_id")
                                .from(Shares::Table, Shares::UserId)
                                .to(Users::Table, Users::Id)
                                .on_delete(ForeignKeyAction::Cascade),
                        )
                        .to_owned(),
                )
                .await
        }

        async fn down(&self, manager: &SchemaManager) -> Result<(), DbErr> {
            manager
                .drop_table(Table::drop().table(Shares::Table).to_owned())
                .await
        }
    }

    #[derive(DeriveIden)]
    enum Shares {
        Table,
        Id,
        UserId,
        FilePath,
        AppType,
        Token,
        ExpiresAt,
        BurnAfterRead,
        MaxViews,
        ViewCount,
        CreatedAt,
    }

    #[derive(DeriveIden)]
    enum Users {
        Table,
        Id,
    }
}
